package com.psyche.memo.provider.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
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

    /** 共用前缀：可见性判定、文本清洗、CSS selector 生成、目标解析。 */
    private const val PRELUDE = """
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
          if (!el) throw new Error('TARGET_NOT_FOUND');
          return el;
        }
        function editability(el) {
          var tag = String(el.tagName || '').toUpperCase();
          if (el.isContentEditable) return 'content';
          if (tag === 'INPUT' || tag === 'TEXTAREA') return 'value';
          throw new Error('NOT_EDITABLE');
        }
    """

    private fun wrap(body: String): String = "(function () { $PRELUDE" +
        " try { $body } catch (err) {" +
        " return JSON.stringify({ ok: false, error: String(err && err.message ? err.message : err) });" +
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
          out.push({
            index: n, tag: tag, role: role,
            text: clean(el.innerText || el.value || el.textContent, $TEXT_CHARS),
            placeholder: clean(el.getAttribute('placeholder'), 60),
            type: clean(el.getAttribute('type'), 20),
            href: el.href ? String(el.href).slice(0, 200) : null,
            selector: selFor(el),
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

    /** 只**写入**并派发 `input`/`change`，绝不派发 `submit`/回车（spec §4：填完就停）。 */
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
        return JSON.stringify({ ok: true, value: { typed: want.length } });
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

    // ------------------------------------------------------------ 参数生成

    fun targetJsonFor(selector: String): String =
        JsonObject(mapOf("selector" to JsonPrimitive(selector))).toString()

    fun targetJsonForPoint(x: Int, y: Int, text: String? = null): String = buildMap<String, JsonElement> {
        put("x", JsonPrimitive(x)); put("y", JsonPrimitive(y))
        text?.let { put("text", JsonPrimitive(it)) }
    }.let(::JsonObject).toString()

    fun targetJsonWithText(selector: String, text: String): String = JsonObject(
        mapOf("selector" to JsonPrimitive(selector), "text" to JsonPrimitive(text)),
    ).toString()

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

    fun parseElements(json: String, generation: Int): BrowserPageSnapshot {
        val obj = Json.parseToJsonElement(json).jsonObject
        val elements = obj["elements"]?.jsonArray?.map { node ->
            val e = node.jsonObject
            val b = e["bounds"]?.jsonObject
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

    /**
     * find 的扫描是否被节点预算/截止时间截停。为真时清单**可能不完整**，Task 6 必须把这句话
     * 带给模型（否则模型会以为「页面上就这 20 个」而漏掉目标）。缺键＝没截停。
     */
    fun findCapped(json: String): Boolean =
        Json.parseToJsonElement(json).jsonObject["capped"]?.jsonPrimitive?.booleanOrNull == true

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
}
