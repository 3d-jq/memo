package com.psyche.memo.provider.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 浏览器侧的 JS：现拼现用，走 `evaluateJavascript`，**不 addJavascriptInterface**（少一层攻击面）。
 *
 * 三条纪律：
 *  1. 需要往脚本里塞数据时，塞的是 **合法 JSON 对象字面量**（kotlinx 负责转义），
 *     不是 JS 字符串拼接 —— 后者是注入的入口；
 *  2. 统一信封 `{ok:true,value:…}` / `{ok:false,error:"CODE"}`，错误码是**枚举**而不是散文；
 *  3. 遍历页面必须带**节点预算 + 截止时间**双闸，否则超大页面会把主线程钉住。
 */
object BrowserScripts {

    private const val TEXT_CHARS = 120
    private const val NODE_BUDGET = 12_000
    private const val DEADLINE_MS = 500L
    private const val READ_HARD_MAX_CHARS = 20_000

    /**
     * `browser_select` 在 JS 里最多扫多少个 option 来找匹配值。
     * 匹配只读 `value`/`textContent`，不量盒子、不强制重排，所以这里可以比
     * [SELECT_OPTION_LIST_LIMIT]（**递给模型**的条数）宽得多：模型没在清单里看到的
     * 第 13 项，只要它把 value 说对了照样选得到。
     */
    private const val OPTION_SCAN_MAX = 2_000

    /** 共用前缀：可见性判定、文本清洗、CSS selector 生成、目标解析。 */
    private const val PRELUDE = """
        function mk(code) { var e = new Error(code); e.memoCode = code; return e; }
        function vis(el) {
          if (!el) return false;
          var r = el.getBoundingClientRect();
          if (r.width < 2 || r.height < 2) return false;
          var cs = window.getComputedStyle(el);
          return cs.visibility !== 'hidden' && cs.display !== 'none' &&
            parseFloat(cs.opacity || '1') > 0.01;
        }
        function clean(s, n) {
          s = String(s == null ? '' : s).replace(/\s+/g, ' ').trim();
          return s.length > n ? s.slice(0, n) : s;
        }
        function selFor(el) {
          if (!el || !(el instanceof Element)) return null;
          if (el.id) {
            try {
              if (document.querySelectorAll('#' + CSS.escape(el.id)).length === 1) {
                return '#' + CSS.escape(el.id);
              }
            } catch (e) {}
          }
          var parts = [], node = el, d = 0;
          while (node && node.nodeType === 1 && node !== document.body && d < 20) {
            var tag = String(node.tagName || '').toLowerCase(), parent = node.parentElement, pos = 0, cnt = 0;
            if (!tag || !parent) break;
            for (var i = 0; i < parent.children.length && i < 2000; i++) {
              if (parent.children[i].tagName === node.tagName) { cnt++; if (parent.children[i] === node) pos = cnt; }
            }
            if (cnt > 1 && pos > 0) tag += ':nth-of-type(' + pos + ')';
            parts.unshift(tag);
            try { if (document.querySelectorAll(parts.join(' > ')).length === 1) return parts.join(' > '); } catch (e) {}
            node = parent; d++;
          }
          return parts.join(' > ');
        }
        function resolve(t) {
          var el = null;
          if (t && typeof t.selector === 'string' && t.selector) {
            try { el = document.querySelector(t.selector); } catch (e) { el = null; }
          } else if (t && typeof t.x === 'number' && typeof t.y === 'number') {
            el = document.elementFromPoint(t.x, t.y);
          }
          if (!el) throw mk('TARGET_NOT_FOUND');
          return el;
        }
        function editability(el) {
          var tag = String(el.tagName || '').toUpperCase();
          if (el.isContentEditable) return 'content';
          if (tag === 'INPUT' || tag === 'TEXTAREA') return 'value';
          throw mk('NOT_EDITABLE');
        }
    """

    private fun wrap(body: String): String = "(function () { $PRELUDE" +
        " try { $body } catch (err) {" +
        " return JSON.stringify({ ok: false, error: err && err.memoCode ? err.memoCode" +
        " : ('JS_ERROR:' + String((err && err.name) || 'Error')) });" +
        " } })()"

    /**
     * 同一个信封，但**不前置 PRELUDE**。
     *
     * 只给「一行属性读取」这种自足的脚本用：PRELUDE 里装着 `vis()`（`getComputedStyle`）、
     * `selFor()` / `resolve()`（`querySelectorAll`）这些会强制布局/遍历的助手，
     * [pageInfoScript] 一支都不许碰。把它们从脚本里整个拿掉，"这一页没被遍历" 才成为
     * 对**全文**成立的断言（`BrowserToolsTest.pageInfoReportsGeometryWithoutScanningThePage`），
     * 而不是「定义了但没调用」那种一有人顺手加一句就失效的口头纪律。
     */
    private fun wrapSelfContained(body: String): String = "(function () {" +
        " try { $body } catch (err) {" +
        " return JSON.stringify({ ok: false, error: 'JS_ERROR:' + String((err && err.name) || 'Error') });" +
        " } })()"

    // ---------------------------------------------------------------- 脚本

    /**
     * 可交互元素清单：编号从 1 起、DOM 顺序、上限 [FIND_LIMIT]。
     *
     * 扫描带双闸，且按**已扫候选数**（JS 里的 `i`）计而不是按已收条目数：每个候选都要量一次
     * 盒子（`getBoundingClientRect` + `getComputedStyle` 都强制布局），可见的不足 [FIND_LIMIT]
     * 时只按 `out.length` 设闸等于在主线程上来一趟无上限的重排扫射。被截停时 `capped` 为真，
     * 已收到的条目照原样返回，由 [findCapped] 读出来告诉模型「清单可能不完整」。
     */
    fun findScript(): String = wrap("""
        var LIMIT = $FIND_LIMIT;
        var cand = Array.prototype.slice.call(document.querySelectorAll(
          'a,button,input,textarea,select,[role],[tabindex],[onclick]'));
        var out = [], n = 0, capped = false;
        var deadline = Date.now() + $DEADLINE_MS;
        for (var i = 0; i < cand.length && out.length < LIMIT; i++) {
          if (i > $NODE_BUDGET || (i % 64 === 0 && Date.now() > deadline)) { capped = true; break; }
          var el = cand[i];
          if (!vis(el)) continue;
          var role = el.getAttribute('role');
          var tag = String(el.tagName).toLowerCase();
          var interactive = role === 'button' || role === 'link' || role === 'textbox' ||
            tag === 'a' || tag === 'button' || tag === 'input' || tag === 'textarea' ||
            tag === 'select' || el.hasAttribute('onclick') || el.hasAttribute('tabindex');
          if (!interactive) continue;
          var r = el.getBoundingClientRect();
          n++;
          // select 要把**能选什么**一起带出来（browser_select 的 value 照着这里填）。
          // 一条 select 最多列 SELECT_OPTION_LIST_LIMIT 项，总数另写在 optionTotal 上 ——
          // 几百项的下拉框整份塞进清单，等于把 20 条元素的可读性换掉。
          var optTotal = 0, optList = null;
          if (tag === 'select' && el.options) {
            optTotal = el.options.length;
            optList = [];
            for (var k = 0; k < optTotal && k < $SELECT_OPTION_LIST_LIMIT; k++) {
              var o = el.options[k];
              optList.push({
                value: clean(o.value, 60),
                text: clean(o.textContent, 60),
                selected: o.selected === true
              });
            }
          }
          out.push({
            index: n, tag: tag, role: role,
            text: clean(el.innerText || el.value || el.textContent, $TEXT_CHARS),
            placeholder: clean(el.getAttribute('placeholder'), 60),
            type: clean(el.getAttribute('type'), 20),
            href: el.href ? String(el.href).slice(0, 200) : null,
            selector: selFor(el),
            options: optList, optionTotal: optTotal,
            bounds: { x: Math.round(r.left), y: Math.round(r.top),
                      width: Math.round(r.width), height: Math.round(r.height) }
          });
        }
        return JSON.stringify({ ok: true, value: { url: location.href,
          title: clean(document.title, $TEXT_CHARS), elements: out, capped: capped } });
    """)

    /**
     * 可见正文：自写 TreeWalker（`innerText` 在离屏 WebView 上不可靠），跳过脚本/样式/图片类标签。
     *
     * `[off, off + limit)` 是**文本流**上的坐标（每段自带行尾），裁窗口在拼串之前做。反过来
     * ——先 join 再 `slice`、游标却按整段长度前进——会把最后一段腰斩还算成整段已读，横跨截断点
     * 的那些字符永远读不到，而调用方是被告知「从 next_offset 继续」的。`nextOffset` 因此只由
     * 实际交付的字符数推进，两页拼起来正好是整篇（不重也不漏）。
     */
    fun readScript(maxChars: Int, offset: Int): String = wrap("""
        var limit = Math.max(1, Math.min($maxChars, $READ_HARD_MAX_CHARS)), off = Math.max(0, $offset);
        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
        var parts = [], cursor = 0, nodes = 0, truncated = false, item;
        var deadline = Date.now() + $DEADLINE_MS;
        while ((item = walker.nextNode())) {
          nodes++;
          if (nodes > $NODE_BUDGET || (nodes % 64 === 0 && Date.now() > deadline)) { truncated = true; break; }
          var p = item.parentElement;
          if (!p) continue;
          var t = String(p.tagName || '').toLowerCase();
          if (t === 'script' || t === 'style' || t === 'noscript' || t === 'template' ||
              t === 'svg' || t === 'canvas' || t === 'iframe' || t === 'video' || t === 'audio') continue;
          if (!vis(p)) continue;
          var s = clean(item.nodeValue, 2000);
          if (!s) continue;
          s += '\n';
          var end = cursor + s.length;
          if (end > off) parts.push(s.slice(Math.max(0, off - cursor), off + limit - cursor));
          cursor = end;
          if (cursor >= off + limit) { truncated = true; break; }
        }
        var text = parts.join('');
        return JSON.stringify({ ok: true, value: { text: text, truncated: truncated,
          offset: off, nextOffset: off + text.length } });
    """)

    /** [targetJson] 是 `{"selector":"…"}` 或 `{"x":n,"y":n}`（由 [targetJsonFor] 生成）。 */
    fun clickScript(targetJson: String): String = wrap("""
        var el = resolve($targetJson);
        var rect = el.getBoundingClientRect();
        el.scrollIntoView({ block: 'center', inline: 'center' });
        ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function (type) {
          el.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window,
            clientX: rect.left + rect.width / 2, clientY: rect.top + rect.height / 2 }));
        });
        return JSON.stringify({ ok: true, value: { clicked: clean(selFor(el), 200) } });
    """)

    /**
     * 写入并派发 `input`/`change`；**只有 target 里带 `submit:true` 才提交**（spec §14，
     * 用户 2026-09-26 拍板放开「绝不提交」那条护栏 —— 它让模型连"把消息发出去"都不敢做）。

     * 提交走两条路，因为真实站点两种都在用：有 `<form>` 就用 `requestSubmit()`（会跑 submit
     * 处理与校验），没有 form 的聊天框（DeepSeek 这类）只能派发一次 Enter 键盘事件 ——
     * 它们的发送逻辑挂在键盘上。`keyCode`/`which` 也得给：只发 `key` 的话很多 React 组件的
     * 判断读不到数字键码，会安静地什么都不做（正是"怎么点不动"的那一类）。
     */
    fun typeScript(targetJson: String): String = wrap("""
        var t = $targetJson;
        var el = resolve(t);
        var mode = editability(el);
        var want = typeof t.text === 'string' ? t.text : '';
        el.focus();
        if (mode === 'value') {
          var proto = el.tagName === 'INPUT' ? window.HTMLInputElement.prototype
            : window.HTMLTextAreaElement.prototype;
          Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, want);
        } else {
          el.textContent = want;
        }
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
        var submitted = false;
        if (t.submit === true) {
          if (el.form && typeof el.form.requestSubmit === 'function') {
            el.form.requestSubmit();
            submitted = true;
          } else {
            ['keydown', 'keypress', 'keyup'].forEach(function (type) {
              el.dispatchEvent(new KeyboardEvent(type, { bubbles: true, cancelable: true,
                key: 'Enter', code: 'Enter', keyCode: 13, which: 13 }));
            });
            submitted = true;
          }
        }
        return JSON.stringify({ ok: true, value: { typed: want.length, submitted: submitted } });
    """)

    /**
     * 滚动一页。符号在这里就算好，插进脚本的永远是**一个**良构数字 —— 把「-」和数值分两处拼，
     * 遇到负的 [amount] 会拼出 `var dy = --800;`，Chromium 读成前缀自减 ⇒ SyntaxError，
     * 整条滚动脚本作废（Task 6 只传正数，这是让生成器自身不留这个口子）。
     */
    fun scrollScript(direction: String, amount: Int): String = wrap("""
        var dy = ${if (direction == "up") -amount else amount};
        window.scrollBy({ top: dy, left: 0, behavior: 'instant' });
        var doc = document.scrollingElement || document.documentElement;
        return JSON.stringify({ ok: true, value: {
          y: Math.round(window.scrollY || doc.scrollTop || 0),
          atTop: (window.scrollY || doc.scrollTop || 0) <= 0,
          atBottom: (window.scrollY || doc.scrollTop || 0) + window.innerHeight >=
            (doc.scrollHeight || 0) - 2 } });
    """)

    /**
     * `<select>` 选值（spec §12.1 的 `browser_select`，本工程新增）。
     *
     * 与 `typeScript` 同一条纪律：**派发 `input`/`change`，绝不提交表单**（不 dispatch
     * `submit`、不调 `form.requestSubmit()`）—— 很多站点的下拉框 change 会自动提交搜索，
     * 那一次「购买/关注/发送」就是从这里溜出去的，所以宁可只改值。
     *
     * 匹配 `value` 或可见文本任一即可（模型在 `find` 清单里看到的常常是人话那半）。
     * **不回显 option 文本**：返回的是索引与总数这两个数字，页面自己控制的字符串不未经
     * 过滤地进上下文（同 `clickScript` 那条纪律）。
     */
    fun selectScript(targetJson: String): String = wrap("""
        var t = $targetJson;
        var el = resolve(t);
        if (String(el.tagName).toUpperCase() !== 'SELECT') throw mk('NOT_SELECTABLE');
        var want = typeof t.value === 'string' ? t.value : '';
        var idx = -1, total = el.options ? el.options.length : 0;
        for (var k = 0; k < total && k < $OPTION_SCAN_MAX; k++) {
          var o = el.options[k];
          if (String(o.value) === want || clean(o.textContent, 200) === want) { idx = k; break; }
        }
        if (idx < 0) throw mk('OPTION_NOT_FOUND');
        el.focus();
        el.selectedIndex = idx;
        el.value = el.options[idx].value;
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
        return JSON.stringify({ ok: true, value: { selectedIndex: idx, options: total } });
    """)

    /**
     * 一支**只做五个属性读取**的「页面指纹」，给动作前后对比用（`browser_click` 的 `page_changed`）。
     *
     * 存在的理由是用户 2026-09-26 实测的那条「它自己说做了，根本没调用工具」：我们的 `click` 派发的是
     * `dispatchEvent` 造的合成事件，`isTrusted=false` —— 现代站点的按钮上很常见地挂着「只认真实手势」
     * 的判断，那时候**点了什么也不会发生**，而工具回一句 `ok` 就等于替模型把谎圆了。参照实现 Eta
     * 也没管这件事（它的 click 只回 `matched_element` 加一个写死的 `side_effect:"possible"`），
     * 所以这条是我们自己补的：**能不能保证成功不能，至少不许假装成功**。
     *
     * 便宜是硬要求：只读标量，不许有遍历（同 [pageInfoScript] 那条纪律）；一次点击多付两发（点前、点后）。
     * 指纹里带 `scrollHeight` 与 `body.childElementCount` 这两项，是因为 SPA 最常见的"点了有反应"
     * 就是弹出/插入了一块 DOM，标题和 url 都不动。
     */
    fun pageSignatureScript(): String = wrapSelfContained("""
        var doc = document.scrollingElement || document.documentElement;
        var body = document.body;
        return JSON.stringify({ ok: true, value: {
          sig: String(location.href).slice(0, 200) + '|' +
            String(document.title || '').replace(/\s+/g, ' ').trim().slice(0, 80) + '|' +
            Math.round(window.scrollY || doc.scrollTop || 0) + '|' +
            Math.round(doc.scrollHeight || 0) + '|' +
            (body ? body.childElementCount : 0) } });
    """)

    /**
     * 页面几何信息（spec §12.1 的 `browser_page_info`，本工程新增）。
     *
     * **只做四次属性读取**：滚动位置/总高/视口高/标题。这里不许出现任何遍历
     * （querySelectorAll / TreeWalker / getComputedStyle 都不许有）—— 元素清单归 `find`、
     * 正文归 `read`，这颗工具的作用就是让模型花最少的钱问一句「我在哪、滚到哪了」；
     * 一旦在里面加遍历，它就变成又一个每页几百毫秒的主线程活。
     * 所以走 [wrapSelfContained]：PRELUDE 里那些会强制布局的助手**整块不在脚本里**，
     * 标题的空白折叠就地写一句（与 PRELUDE 的 `clean()` 同一个正则）。
     */
    fun pageInfoScript(): String = wrapSelfContained("""
        var doc = document.scrollingElement || document.documentElement;
        var y = Math.round(window.scrollY || doc.scrollTop || 0);
        var total = Math.round(doc.scrollHeight || 0);
        var vh = Math.round(window.innerHeight || 0);
        var title = String(document.title || '').replace(/\s+/g, ' ').trim().slice(0, $TEXT_CHARS);
        return JSON.stringify({ ok: true, value: {
          url: String(location.href).slice(0, 300),
          title: title,
          scrollY: y, scrollHeight: total, viewportHeight: vh,
          atTop: y <= 0, atBottom: total - (y + vh) <= 2 } });
    """)

    // ------------------------------------------------------------ 参数生成

    /**
     * `target` 字面量：`{"selector":"…"}` + 这颗动作要额外带的字段（`type` 的 `text`、
     * `browser_select` 的 `value`）。
     *
     * 附加字段走 [extras] 而不是各建一支函数：转义永远交给 kotlinx，
     * 「两条路径只有一条记得转义」这种口子就无从产生（纪律 1）。
     */
    fun targetJsonFor(
        selector: String,
        extras: Map<String, JsonElement> = emptyMap(),
    ): String = JsonObject(mapOf("selector" to JsonPrimitive(selector)) + extras).toString()

    /** 坐标寻址的 `target`（`{"x":n,"y":n}` + [extras]），语义同 [targetJsonFor]。 */
    fun targetJsonForPoint(
        x: Int,
        y: Int,
        extras: Map<String, JsonElement> = emptyMap(),
    ): String = JsonObject(mapOf("x" to JsonPrimitive(x), "y" to JsonPrimitive(y)) + extras).toString()

    // ---------------------------------------------------------------- 解析

    /**
     * 解开通信信封。
     *
     * 第一步剥 `evaluateJavascript` 的那层额外编码：脚本返回的是**字符串**，回调给的
     * 是它的 JSON 字面量（`"\"{\\\"ok\\\":true…}\""`）。不剥就处处 `BAD_JSON`。
     */
    fun unwrap(raw: String): Pair<Boolean, String> {
        val outer = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return false to "BAD_JSON"
        val body = if (outer is JsonPrimitive && outer.isString) outer.content else outer.toString()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }
            .getOrNull() ?: return false to "BAD_JSON"
        val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull == true
        return if (ok) {
            true to (obj["value"]?.toString() ?: "null")
        } else {
            false to ((obj["error"] as? JsonPrimitive)?.contentOrNull ?: "UNKNOWN")
        }
    }

    /**
     * 解析**页面控制的**容器字段一律走这两个 `as?`，不许用 `?.jsonArray` / `?.jsonObject`。
     *
     * 那两条扩展函数遇到 `JsonNull` 是**抛** `IllegalArgumentException`（"Element class JsonNull
     * is not a JsonArray"），而 `findScript` 对每个非 select 元素都回 `"options": null` —— 于是
     * 一次普通的 `browser_find` 会把整颗工具调用炸掉（`everyDeclaredToolIsImplemented` 拿
     * 真实形状的 find 回包喂进来，第一次就抓到了它）。缺键与 JSON null 在这里是同一件事：
     * 「页面没给这个容器」。
     */
    fun parseElements(json: String, generation: Int): BrowserPageSnapshot {
        val obj = Json.parseToJsonElement(json).jsonObject
        val elements = (obj["elements"] as? JsonArray)?.map { node ->
            val e = node.jsonObject
            val b = e["bounds"] as? JsonObject
            // option 条数在 JS 侧已经过 SELECT_OPTION_LIST_LIMIT；这里再 take 一次是**解析端
            // 的第二道闸**（与 textOrNull 的空白折叠同一理由：页面的东西不许原样进上下文）。
            val options = (e["options"] as? JsonArray)?.map { o ->
                val item = o.jsonObject
                SelectOption(
                    value = item["value"].text(),
                    text = item["text"].text(),
                    selected = (item["selected"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }.orEmpty().take(SELECT_OPTION_LIST_LIMIT)
            BrowserElement(
                index = e["index"].intOr(0),
                tag = e["tag"].text(),
                role = e["role"].textOrNull(),
                text = e["text"].text(),
                placeholder = e["placeholder"].textOrNull(),
                type = e["type"].textOrNull(),
                href = e["href"].textOrNull(),
                selector = e["selector"].text(),
                bounds = Bounds(
                    x = b?.get("x").intOr(0), y = b?.get("y").intOr(0),
                    width = b?.get("width").intOr(0), height = b?.get("height").intOr(0),
                ),
                options = options,
                optionTotal = e["optionTotal"].intOr(options.size),
            )
        }.orEmpty()
        return BrowserPageSnapshot(
            generation = generation,
            url = obj["url"].text(),
            title = obj["title"].text(),
            elements = elements,
        )
    }

    /** → 正文 + 是否被截断（截断时调用方必须给 next_offset，别让模型以为读完了）。 */
    fun parseRead(json: String): Pair<String, Boolean> {
        val obj = Json.parseToJsonElement(json).jsonObject
        return obj["text"].text() to (obj["truncated"]?.jsonPrimitive?.booleanOrNull == true)
    }

    fun readNextOffset(json: String): Int =
        Json.parseToJsonElement(json).jsonObject["nextOffset"].intOr(0)

    /** 动作前后那一次「页面指纹」；取不到（缺键 / 空串 / 脚本没跑成）一律 null = **不知道**。 */
    fun parsePageSignature(json: String): String? =
        Json.parseToJsonElement(json).jsonObject["sig"].textOrNull()?.takeIf { it.isNotBlank() }

    /**
     * find 的扫描是否被节点预算/截止时间截停。为真时清单**可能不完整**，Task 6 必须把这句话
     * 带给模型（否则模型会以为「页面上就这 20 个」而漏掉目标）。缺键＝没截停。
     */
    fun findCapped(json: String): Boolean =
        Json.parseToJsonElement(json).jsonObject["capped"]?.jsonPrimitive?.booleanOrNull == true

    /** `browser_page_info` 的那一小包几何信息（[pageInfoScript] 的 value）。 */
    fun parsePageInfo(json: String): BrowserPageInfo {
        val obj = Json.parseToJsonElement(json).jsonObject
        return BrowserPageInfo(
            url = obj["url"].text(),
            title = obj["title"].text(),
            scrollY = obj["scrollY"].intOr(0),
            scrollHeight = obj["scrollHeight"].intOr(0),
            viewportHeight = obj["viewportHeight"].intOr(0),
            atTop = obj["atTop"]?.jsonPrimitive?.booleanOrNull == true,
            atBottom = obj["atBottom"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }

    /** JS 抛出的错误码 → 枚举；不认识的（真·JS 异常）返回 null。 */
    fun parseJsErrorCode(payload: String): BrowserJsError? =
        BrowserJsError.values().firstOrNull { it.code == payload }

    private fun JsonElement?.text(): String = textOrNull().orEmpty()

    /**
     * 空白折叠是**解析端的第二道闸**，不依赖 JS 侧的 `clean()`：清单渲染成「一行一条」后，
     * 字段里夹一个 `\n` 就能把一行劈成两行、甚至伪造出一条假条目（注入面）。JS 漏了
     * （或走了没经 `clean` 的路径）也不能污染给模型的文本。
     */
    private fun JsonElement?.textOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            ?.let { WHITESPACE.replace(it, " ").trim() }

    private fun JsonElement?.intOr(default: Int): Int =
        (this as? JsonPrimitive)?.intOrNull ?: default

    private val WHITESPACE = Regex("\\s+")
}

/** JS 侧**自己**抛的错误码（`Error(message)`），决定给模型哪句补救。 */
enum class BrowserJsError(val code: String) {
    TARGET_NOT_FOUND("TARGET_NOT_FOUND"),
    NOT_EDITABLE("NOT_EDITABLE"),

    /** `browser_select` 打到了非 `<select>` 元素上（新动作，spec §12.1）。 */
    NOT_SELECTABLE("NOT_SELECTABLE"),

    /** `browser_select` 的 `value` 在这个下拉框里既不对不上 value、也不对上文本。 */
    OPTION_NOT_FOUND("OPTION_NOT_FOUND"),
}
