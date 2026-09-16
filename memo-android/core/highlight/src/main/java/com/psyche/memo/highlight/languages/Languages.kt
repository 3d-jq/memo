package com.psyche.memo.highlight.languages

import com.psyche.memo.highlight.core.Language
import com.psyche.memo.highlight.languages.bash.bash
import com.psyche.memo.highlight.languages.c.c
import com.psyche.memo.highlight.languages.cmake.cmake
import com.psyche.memo.highlight.languages.cpp.cpp
import com.psyche.memo.highlight.languages.csharp.csharp
import com.psyche.memo.highlight.languages.css.css
import com.psyche.memo.highlight.languages.dart.dart
import com.psyche.memo.highlight.languages.diff.diff
import com.psyche.memo.highlight.languages.dockerfile.dockerfile
import com.psyche.memo.highlight.languages.go.go
import com.psyche.memo.highlight.languages.glsl.glsl
import com.psyche.memo.highlight.languages.ini.ini
import com.psyche.memo.highlight.languages.java.java
import com.psyche.memo.highlight.languages.javascript.javascript
import com.psyche.memo.highlight.languages.json.json
import com.psyche.memo.highlight.languages.kotlin.kotlin
import com.psyche.memo.highlight.languages.latex.latex
import com.psyche.memo.highlight.languages.lua.lua
import com.psyche.memo.highlight.languages.markdown.markdown
import com.psyche.memo.highlight.languages.php.php
import com.psyche.memo.highlight.languages.powershell.powershell
import com.psyche.memo.highlight.languages.properties.properties
import com.psyche.memo.highlight.languages.python.python
import com.psyche.memo.highlight.languages.rust.rust
import com.psyche.memo.highlight.languages.ruby.ruby
import com.psyche.memo.highlight.languages.sql.sql
import com.psyche.memo.highlight.languages.swift.swift
import com.psyche.memo.highlight.languages.typescript.typescript
import com.psyche.memo.highlight.languages.xml.xml
import com.psyche.memo.highlight.languages.yaml.yaml

/**
 * Every grammar bundled with the highlighter.
 *
 * Each entry builds a fresh mode tree: compilation mutates modes in place, mirroring `highlight.js`.
 */
internal fun builtinLanguages(): List<Language> = listOf(
    json(),
    ini(),
    cmake(),
    go(),
    glsl(),
    yaml(),
    bash(),
    dockerfile(),
    javascript(),
    typescript(),
    xml(),
    css(),
    dart(),
    java(),
    kotlin(),
    latex(),
    lua(),
    powershell(),
    properties(),
    python(),
    c(),
    cpp(),
    csharp(),
    sql(),
    diff(),
    markdown(),
    rust(),
    ruby(),
    php(),
    swift(),
)
