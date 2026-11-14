package com.fastdownloader.app.engine

import com.fastdownloader.app.engine.AdBlock.NetRule
import com.fastdownloader.app.engine.AdBlock.ParamRule
import com.fastdownloader.app.engine.AdBlock.Scriptlet
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A compact binary copy of a built [AdBlock], so the next launch can read it back in a fraction of the time
 * it takes to parse the filter lists again (see [Filters]). Everything the blocker has indexed is written as it
 * is held in memory: host sets, rule lists, cosmetic selectors and scriptlets. Regular expressions are stored as
 * text and compiled the first time a request needs them, as after a normal parse.
 *
 * The file starts with [FORMAT_VERSION] and the caller's validity key; [read] throws [IllegalStateException] for
 * anything it does not recognise, and the caller then parses the lists instead.
 * Plain Kotlin (no Android classes), so it can be unit-tested on the PC.
 */
object AdBlockSnapshot {
    /** Bump whenever the layout below, or the way [AdBlock.parse] files rules, changes. */
    const val FORMAT_VERSION = 1

    private const val MAGIC = 0x47534E50 // "GSNP"
    private const val IO_BUFFER = 64 * 1024
    private const val NO_ARRAY = -1

    /** Writes [ab] to [target] under [key]. The caller is responsible for flushing and closing [target]. */
    fun write(ab: AdBlock, key: String, target: OutputStream) {
        val out = DataOutputStream(target.buffered(IO_BUFFER))
        out.writeInt(MAGIC)
        out.writeInt(FORMAT_VERSION)
        out.writeBytesUtf(key)
        out.writeInt(ab.networkRules)
        out.writeInt(ab.cosmeticRules)
        out.writeInt(ab.scriptletRules)
        for (m in ruleMaps(ab)) out.writeMap(m) { writeRule(it) }
        for (s in hostSets(ab)) out.writeHostBlock(s)
        out.writeStrings(ab.genericAllow)
        out.writeStrings(ab.genericHigh)
        for (m in selectorMaps(ab)) out.writeMap(m) { writeBytesUtf(it) }
        for (m in selectorSetMaps(ab)) out.writeMap(m) { writeBytesUtf(it) }
        out.writeMap(ab.scriptlets) { writeScriptlet(it) }
        out.writeMap(ab.scriptletAllow) { writeBytesUtf(it) }
        out.writeList(ab.paramRules) { writeParamRule(it) }
        out.writeList(ab.docAllow) { writeRule(it.first); writeInt(it.second) }
        out.flush()
    }

    /** Reads a snapshot back. Returns null when it was written under a different key; throws when it is unreadable. */
    fun read(source: InputStream, key: String): AdBlock? {
        val input = DataInputStream(source.buffered(IO_BUFFER))
        check(input.readInt() == MAGIC) { "not a snapshot" }
        check(input.readInt() == FORMAT_VERSION) { "snapshot format changed" }
        if (input.readBytesUtf() != key) return null
        val ab = AdBlock()
        ab.networkRules = input.readInt()
        ab.cosmeticRules = input.readInt()
        ab.scriptletRules = input.readInt()
        for (m in ruleMaps(ab)) input.readMap(m, { readRule() }) { ArrayList(1) }
        for (s in hostSets(ab)) input.readHostBlock(s)
        input.readStrings(ab.genericAllow)
        input.readStrings(ab.genericHigh)
        for (m in selectorMaps(ab)) input.readMap(m, { readBytesUtf() }) { ArrayList(1) }
        for (m in selectorSetMaps(ab)) input.readMap(m, { readBytesUtf() }) { HashSet() }
        input.readMap(ab.scriptlets, { readScriptlet() }) { ArrayList(1) }
        input.readMap(ab.scriptletAllow, { readBytesUtf() }) { HashSet() }
        input.readList(ab.paramRules) { readParamRule() }
        input.readList(ab.docAllow) { readRule() to readInt() }
        return ab
    }

    // The order of these lists is part of the format.
    private fun ruleMaps(ab: AdBlock) = listOf(ab.blockHosts, ab.allowHosts, ab.blockTokens, ab.allowTokens, ab.redirectHosts, ab.redirectTokens)
    private fun hostSets(ab: AdBlock) = listOf(ab.plainHosts, ab.dnsHosts, ab.dnsAllow)
    private fun selectorMaps(ab: AdBlock) = listOf(ab.genericByToken, ab.specific, ab.procedural)
    private fun selectorSetMaps(ab: AdBlock) = listOf(ab.specificAllow, ab.proceduralAllow)

    // ---------------- records ----------------

    private fun DataOutputStream.writeRule(r: NetRule) {
        writeBytesUtf(r.pattern)
        writeInt(r.types)
        writeInt(r.notTypes)
        writeByte(r.party)
        writeBoolean(r.important)
        writeNullable(r.redirect) { writeBytesUtf(it) }
        writeNullable(r.regexSource) { writeBytesUtf(it) }
        writeArray(r.domains)
        writeArray(r.notDomains)
    }

    private fun DataInputStream.readRule(): NetRule {
        val pattern = readBytesUtf()
        val types = readInt()
        val notTypes = readInt()
        val party = readByte().toInt()
        val important = readBoolean()
        val redirect = readNullable { readBytesUtf() }
        val regexSource = readNullable { readBytesUtf() }
        val domains = readArray()
        val notDomains = readArray()
        return NetRule(pattern, types, notTypes, party, domains, notDomains, important, redirect, null, regexSource)
    }

    private fun DataOutputStream.writeScriptlet(s: Scriptlet) {
        writeBytesUtf(s.key)
        writeStrings(s.args)
        writeStrings(s.notDomains)
    }

    private fun DataInputStream.readScriptlet(): Scriptlet {
        val key = readBytesUtf()
        val args = ArrayList<String>().also { readStrings(it) }
        val notDomains = ArrayList<String>().also { readStrings(it) }
        return Scriptlet(key, args, notDomains)
    }

    private fun DataOutputStream.writeParamRule(p: ParamRule) {
        writeRule(p.rule)
        writeBytesUtf(p.name)
        writeNullable(p.regex?.pattern) { writeBytesUtf(it) }
    }

    private fun DataInputStream.readParamRule(): ParamRule {
        val rule = readRule()
        val name = readBytesUtf()
        val regex = readNullable { readBytesUtf() }?.let { Regex(it) }
        return ParamRule(rule, name, regex)
    }

    // ---------------- primitives ----------------

    private fun DataOutputStream.writeBytesUtf(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBytesUtf(): String {
        val size = readInt()
        check(size >= 0) { "bad string length" }
        val bytes = ByteArray(size)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Host names (letters, digits, dots, dashes and underscores only) as one newline-separated block: the three
     * big sets hold hundreds of thousands of them, and one read plus a split is far quicker than one read each.
     */
    private fun DataOutputStream.writeHostBlock(hosts: Collection<String>) {
        writeInt(hosts.size)
        writeBytesUtf(hosts.joinToString("\n"))
    }

    private fun DataInputStream.readHostBlock(into: MutableCollection<String>) {
        val count = readInt()
        check(count >= 0) { "bad host count" }
        val block = readBytesUtf()
        var start = 0
        repeat(count) {
            var end = block.indexOf('\n', start)
            if (end < 0) end = block.length
            into.add(block.substring(start, end))
            start = end + 1
        }
    }

    private fun DataOutputStream.writeStrings(items: Collection<String>) = writeList(items) { writeBytesUtf(it) }

    private fun DataInputStream.readStrings(into: MutableCollection<String>) = readList(into) { readBytesUtf() }

    private fun DataOutputStream.writeArray(items: Array<String>?) {
        if (items == null) writeInt(NO_ARRAY) else writeStrings(items.asList())
    }

    private fun DataInputStream.readArray(): Array<String>? {
        val size = readInt()
        if (size == NO_ARRAY) return null
        check(size >= 0) { "bad array length" }
        return Array(size) { readBytesUtf() }
    }

    private fun <T> DataOutputStream.writeNullable(v: T?, writer: DataOutputStream.(T) -> Unit) {
        writeBoolean(v != null)
        if (v != null) writer(v)
    }

    private fun <T> DataInputStream.readNullable(reader: DataInputStream.() -> T): T? = if (readBoolean()) reader() else null

    private fun <T> DataOutputStream.writeList(items: Collection<T>, writer: DataOutputStream.(T) -> Unit) {
        writeInt(items.size)
        for (item in items) writer(item)
    }

    private fun <T> DataInputStream.readList(into: MutableCollection<T>, reader: DataInputStream.() -> T) {
        val size = readInt()
        check(size >= 0) { "bad list length" }
        repeat(size) { into.add(reader()) }
    }

    private fun <T> DataOutputStream.writeMap(map: Map<String, Collection<T>>, writer: DataOutputStream.(T) -> Unit) {
        writeInt(map.size)
        for ((k, v) in map) {
            writeBytesUtf(k)
            writeList(v, writer)
        }
    }

    private fun <T, C : MutableCollection<T>> DataInputStream.readMap(
        into: MutableMap<String, C>, reader: DataInputStream.() -> T, newCollection: () -> C,
    ) {
        val size = readInt()
        check(size >= 0) { "bad map length" }
        repeat(size) {
            val key = readBytesUtf()
            into[key] = newCollection().also { readList(it, reader) }
        }
    }
}
