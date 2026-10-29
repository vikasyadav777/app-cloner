package com.appcloner.dual.clone

internal object ManifestRewriter {
    private val classElements = setOf(
        "application",
        "activity",
        "activity-alias",
        "service",
        "receiver",
        "provider",
    )

    private val permissionElements = setOf(
        "permission",
        "uses-permission",
        "uses-permission-sdk-23",
        "permission-tree",
        "permission-group",
    )

    private val identityAttributes = listOf(
        "authorities",
        "permission",
        "readPermission",
        "writePermission",
        "process",
        "taskAffinity",
        "targetPackage",
    )

    fun rewrite(xml: ByteArray, oldPackage: String, newPackage: String, newLabel: String): ByteArray {
        require(xml.size >= 8 && u16(xml, 0) == 0x0003) {
            "This app doesn't have a standard Android manifest, so it can't be cloned."
        }
        val rootHeaderSize = u16(xml, 2)
        val rootSize = u32(xml, 4)
        require(rootHeaderSize >= 8 && rootSize <= xml.size && u16(xml, rootHeaderSize) == 0x0001) {
            "This app's manifest couldn't be read."
        }
        val poolSize = u32(xml, rootHeaderSize + 4)
        val pool = StringPool.parse(xml.copyOfRange(rootHeaderSize, rootHeaderSize + poolSize))
        val tailEnd = minOf(rootSize, xml.size)
        val tail = xml.copyOfRange(rootHeaderSize + poolSize, tailEnd)
        val strings = pool.strings
        val labelIndex = strings.size
        strings.add(newLabel)

        val nodes = ArrayList<ByteArray>()
        val infos = ArrayList<Info>()
        val stack = ArrayDeque<Int>()
        var sawPackage = false
        var pos = 0
        while (pos + 8 <= tail.size) {
            val size = u32(tail, pos + 4)
            if (size < 8 || pos + size > tail.size) {
                nodes += tail.copyOfRange(pos, tail.size)
                break
            }
            var chunk = tail.copyOfRange(pos, pos + size)
            if (u16(chunk, 0) == 0x0102) {
                val name = elementName(chunk, strings)
                chunk = rewriteIdentity(chunk, strings, name, oldPackage, newPackage) { sawPackage = true }
                val parent = stack.lastOrNull() ?: -1
                infos += Info(nodes.size, name, parent)
                stack.addLast(infos.lastIndex)
            } else if (u16(chunk, 0) == 0x0103 && stack.isNotEmpty()) {
                stack.removeLast()
            }
            nodes += chunk
            pos += size
        }
        if (!sawPackage) {
            throw IllegalArgumentException("This app's manifest has no package name.")
        }
        markLaunchers(infos, nodes, strings)
        var addedLabel = false
        for (info in infos) {
            val wantsLabel = info.name == "application" || info.launcher
            if (!wantsLabel) continue
            val updated = retargetLabel(nodes[info.nodeIndex], strings, labelIndex)
            if (updated !== nodes[info.nodeIndex]) addedLabel = true
            nodes[info.nodeIndex] = updated
        }
        if (addedLabel) {
            val nameIndex = strings.indexOf("label")
            for (i in nodes.indices) {
                if (u16(nodes[i], 0) == 0x0180) {
                    nodes[i] = ensureLabelResource(nodes[i], nameIndex)
                }
            }
        }
        val newPool = StringPool.build(pool)
        val tailSize = nodes.sumOf { it.size }
        val outSize = rootHeaderSize + newPool.size + tailSize
        val out = ByteArray(outSize)
        xml.copyInto(out, 0, 0, rootHeaderSize)
        putU32(out, 4, outSize)
        newPool.copyInto(out, rootHeaderSize)
        var cursor = rootHeaderSize + newPool.size
        for (node in nodes) {
            node.copyInto(out, cursor)
            cursor += node.size
        }
        return out
    }

    private fun markLaunchers(infos: List<Info>, nodes: List<ByteArray>, strings: List<String>) {
        for (index in infos.indices) {
            val info = infos[index]
            if (info.name != "activity" && info.name != "activity-alias") continue
            var main = false
            var launcher = false
            for (other in infos.indices) {
                if (!isDescendant(infos, other, index)) continue
                val child = infos[other]
                val value = attrString(nodes[child.nodeIndex], strings, "name") ?: continue
                if (child.name == "action" && value == "android.intent.action.MAIN") main = true
                if (child.name == "category" && value == "android.intent.category.LAUNCHER") launcher = true
            }
            info.launcher = main && launcher
        }
    }

    private fun isDescendant(infos: List<Info>, child: Int, ancestor: Int): Boolean {
        var parent = infos[child].parent
        while (parent >= 0) {
            if (parent == ancestor) return true
            parent = infos[parent].parent
        }
        return false
    }

    private fun rewriteIdentity(
        chunk: ByteArray,
        strings: MutableList<String>,
        element: String,
        oldPackage: String,
        newPackage: String,
        onPackage: () -> Unit,
    ): ByteArray {
        var current = chunk
        if (element == "manifest") {
            val packageAttr = findAttr(current, strings, "package")
            if (packageAttr >= 0) {
                val value = attrString(current, strings, "package")
                if (value != oldPackage) {
                    throw IllegalArgumentException("Can't clone this app: its package is $value.")
                }
                setAttrString(current, packageAttr, intern(strings, newPackage))
                onPackage()
            }
        }
        if (element in classElements) {
            absolutizeClass(current, strings, "name", oldPackage)
            if (element == "activity-alias") absolutizeClass(current, strings, "targetActivity", oldPackage)
            if (element == "application") {
                absolutizeClass(current, strings, "backupAgent", oldPackage)
                absolutizeClass(current, strings, "appComponentFactory", oldPackage)
            }
        }
        if (element in permissionElements) {
            renameAttr(current, strings, "name", oldPackage, newPackage, split = false)
        }
        for (attribute in identityAttributes) {
            val split = attribute == "authorities"
            renameAttr(current, strings, attribute, oldPackage, newPackage, split)
        }
        if (element == "manifest") {
            val shared = findAttr(current, strings, "sharedUserId")
            if (shared >= 0) current = removeAttr(current, shared)
        }
        return current
    }

    private fun renameAttr(
        chunk: ByteArray,
        strings: MutableList<String>,
        attribute: String,
        oldPackage: String,
        newPackage: String,
        split: Boolean,
    ) {
        val index = findAttr(chunk, strings, attribute)
        if (index < 0) return
        val value = attrString(chunk, strings, attribute) ?: return
        val renamed = if (split) {
            value.split(';').joinToString(";") { part ->
                renameIdentity(part.trim(), oldPackage, newPackage) ?: part.trim()
            }
        } else {
            renameIdentity(value, oldPackage, newPackage)
        }
        if (renamed != null && renamed != value) {
            setAttrString(chunk, index, intern(strings, renamed))
        }
    }

    private fun absolutizeClass(chunk: ByteArray, strings: MutableList<String>, attribute: String, oldPackage: String) {
        val index = findAttr(chunk, strings, attribute)
        if (index < 0) return
        val value = attrString(chunk, strings, attribute) ?: return
        val absolute = when {
            value.startsWith(".") -> oldPackage + value
            !value.contains('.') -> "$oldPackage.$value"
            else -> return
        }
        setAttrString(chunk, index, intern(strings, absolute))
    }

    private fun renameIdentity(value: String, oldPackage: String, newPackage: String): String? {
        if (value == oldPackage) return newPackage
        if (value.startsWith("$oldPackage.") || value.startsWith("$oldPackage:") || value.startsWith("$oldPackage/")) {
            return newPackage + value.substring(oldPackage.length)
        }
        return null
    }

    private fun retargetLabel(chunk: ByteArray, strings: MutableList<String>, labelIndex: Int): ByteArray {
        val existing = findAttr(chunk, strings, "label")
        if (existing >= 0) {
            setAttrString(chunk, existing, labelIndex)
            return chunk
        }
        if (elementName(chunk, strings) != "application") return chunk
        val androidNs = strings.indexOf("http://schemas.android.com/apk/res/android")
        var labelName = strings.indexOf("label")
        if (labelName < 0) {
            labelName = strings.size
            strings.add("label")
        }
        return addAttr(chunk, androidNs, labelName, labelIndex)
    }

    private fun ensureLabelResource(chunk: ByteArray, nameIndex: Int): ByteArray {
        if (nameIndex < 0) return chunk
        val headerSize = u16(chunk, 2)
        val count = (chunk.size - headerSize) / 4
        if (nameIndex < count) {
            if (u32(chunk, headerSize + nameIndex * 4) == 0) {
                putU32(chunk, headerSize + nameIndex * 4, 0x01010001)
            }
            return chunk
        }
        val grown = chunk.copyOf(headerSize + (nameIndex + 1) * 4)
        putU32(grown, 4, grown.size)
        putU32(grown, headerSize + nameIndex * 4, 0x01010001)
        return grown
    }

    private fun elementName(chunk: ByteArray, strings: List<String>): String {
        val headerSize = u16(chunk, 2)
        val index = u32(chunk, headerSize + 4)
        return strings.getOrElse(index) { "" }
    }

    private fun attrOffset(chunk: ByteArray, index: Int): Int {
        val headerSize = u16(chunk, 2)
        val attrStart = u16(chunk, headerSize + 8)
        val attrSize = u16(chunk, headerSize + 10)
        return headerSize + attrStart + index * attrSize
    }

    private fun findAttr(chunk: ByteArray, strings: List<String>, name: String): Int {
        val headerSize = u16(chunk, 2)
        val count = u16(chunk, headerSize + 12)
        for (i in 0 until count) {
            val nameIndex = u32(chunk, attrOffset(chunk, i) + 4)
            if (nameIndex in strings.indices && strings[nameIndex] == name) return i
        }
        return -1
    }

    private fun attrString(chunk: ByteArray, strings: List<String>, name: String): String? {
        val index = findAttr(chunk, strings, name)
        if (index < 0) return null
        val off = attrOffset(chunk, index)
        val raw = u32(chunk, off + 8)
        val type = chunk[off + 15].toInt() and 0xFF
        val data = u32(chunk, off + 16)
        val stringIndex = when {
            type == 0x03 && data >= 0 -> data
            raw >= 0 -> raw
            else -> return null
        }
        return strings.getOrNull(stringIndex)
    }

    private fun setAttrString(chunk: ByteArray, index: Int, stringIndex: Int) {
        val off = attrOffset(chunk, index)
        putU32(chunk, off + 8, stringIndex)
        chunk[off + 15] = 0x03
        putU32(chunk, off + 16, stringIndex)
    }

    private fun addAttr(chunk: ByteArray, ns: Int, name: Int, value: Int): ByteArray {
        val headerSize = u16(chunk, 2)
        val attrStart = u16(chunk, headerSize + 8)
        val attrSize = u16(chunk, headerSize + 10)
        val attrCount = u16(chunk, headerSize + 12)
        require(attrSize == 20) { "This manifest uses an unsupported attribute size." }
        val arrayEnd = headerSize + attrStart + attrCount * attrSize
        val attr = ByteArray(20)
        putU32(attr, 0, ns)
        putU32(attr, 4, name)
        putU32(attr, 8, value)
        putU16(attr, 12, 8)
        attr[15] = 0x03
        putU32(attr, 16, value)
        val out = ByteArray(chunk.size + attrSize)
        chunk.copyInto(out, 0, 0, arrayEnd)
        attr.copyInto(out, arrayEnd)
        chunk.copyInto(out, arrayEnd + attrSize, arrayEnd, chunk.size)
        putU32(out, 4, out.size)
        putU16(out, headerSize + 12, attrCount + 1)
        return out
    }

    private fun removeAttr(chunk: ByteArray, index: Int): ByteArray {
        val headerSize = u16(chunk, 2)
        val attrStart = u16(chunk, headerSize + 8)
        val attrSize = u16(chunk, headerSize + 10)
        val attrCount = u16(chunk, headerSize + 12)
        val idIndex = u16(chunk, headerSize + 14)
        val classIndex = u16(chunk, headerSize + 16)
        val styleIndex = u16(chunk, headerSize + 18)
        val start = headerSize + attrStart + index * attrSize
        val end = start + attrSize
        val out = ByteArray(chunk.size - attrSize)
        chunk.copyInto(out, 0, 0, start)
        chunk.copyInto(out, start, end, chunk.size)
        putU32(out, 4, out.size)
        putU16(out, headerSize + 12, attrCount - 1)
        putU16(out, headerSize + 14, adjustIndex(idIndex, index))
        putU16(out, headerSize + 16, adjustIndex(classIndex, index))
        putU16(out, headerSize + 18, adjustIndex(styleIndex, index))
        return out
    }

    private fun adjustIndex(index: Int, removed: Int): Int {
        if (index == 0) return 0
        val zero = index - 1
        if (zero == removed) return 0
        if (zero > removed) return index - 1
        return index
    }

    private fun intern(strings: MutableList<String>, value: String): Int {
        val existing = strings.indexOf(value)
        if (existing >= 0) return existing
        strings.add(value)
        return strings.lastIndex
    }

    private class Info(
        val nodeIndex: Int,
        val name: String,
        val parent: Int,
        var launcher: Boolean = false,
    )
}
