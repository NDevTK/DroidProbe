package com.droidprobe.app.analysis.dex

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.droidprobe.app.data.model.CrudOperationInfo

/**
 * Detects ContentProvider CRUD operations and query metadata:
 * - insert(): ContentValues.getAsString/getAsInteger/getAsLong/put key extraction
 * - update(): same ContentValues keys + selection string patterns
 * - delete(): selection string patterns
 * - getType(): MIME type return values
 * - query(): MatrixCursor projection columns, selection templates, sort orders
 * - openFile(): file access mode strings ("r", "rw", etc.)
 *
 * Only processes classes that extend ContentProvider (via class hierarchy).
 */
class ContentProviderCrudExtractor(
    private val classHierarchy: Map<String, String>
) {
    private val results = mutableListOf<CrudOperationInfo>()
    private val seenOps = mutableSetOf<String>()

    private val selectionPattern = Regex("""=\s*\?|LIKE\s+\?|IN\s*\(\s*\?""", RegexOption.IGNORE_CASE)
    private val sortOrderPattern = Regex("""\b\w+\s+(ASC|DESC)\b""", RegexOption.IGNORE_CASE)
    private val validOpenFileModes = setOf("r", "rw", "w", "rwt")

    fun process(classDef: DexBackedClassDef) {
        if (!isContentProviderClass(classDef.type)) return

        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val instructions = impl.instructions.toList()
            if (instructions.isEmpty()) continue

            val cfg = MethodCFG(instructions, impl.tryBlocks)
            val cfgStrings = cfg.computeStringRegisters()
            val methodName = method.name

            // Determine operation type from method name
            val operation = when (methodName) {
                "insert" -> "INSERT"
                "update" -> "UPDATE"
                "delete" -> "DELETE"
                "getType" -> "GET_TYPE"
                "query" -> "QUERY"
                "openFile" -> "OPEN_FILE"
                else -> null
            } ?: continue

            when (operation) {
                "QUERY" -> processQuery(classDef, methodName, instructions, cfgStrings)
                "OPEN_FILE" -> processOpenFile(classDef, methodName, instructions, cfgStrings)
                else -> processLegacy(classDef, methodName, operation, instructions, cfgStrings)
            }
        }
    }

    private fun processLegacy(
        classDef: DexBackedClassDef,
        methodName: String,
        operation: String,
        instructions: List<Instruction>,
        cfgStrings: Array<Map<Int, String>?>
    ) {
        val contentValuesKeys = mutableSetOf<String>()
        val mimeTypes = mutableSetOf<String>()

        for ((i, instr) in instructions.withIndex()) {
            // getType(): scan all const-string instructions for MIME-type patterns
            if (operation == "GET_TYPE" && instr is ReferenceInstruction) {
                val strRef = instr.reference as? StringReference
                if (strRef != null) {
                    val str = strRef.string
                    if (str.startsWith("vnd.") || str.contains("/vnd.") ||
                        str.startsWith("application/") || str.startsWith("text/") ||
                        str.startsWith("image/") || str.startsWith("video/") ||
                        str.startsWith("audio/")) {
                        mimeTypes.add(str)
                    }
                }
            }

            if (instr !is ReferenceInstruction) continue
            val ref = instr.reference as? MethodReference ?: continue

            when {
                // ContentValues.getAsXxx(key) — reading keys in insert/update
                ref.definingClass == "Landroid/content/ContentValues;" &&
                    ref.name.startsWith("getAs") &&
                    ref.parameterTypes.size == 1 &&
                    ref.parameterTypes[0] == "Ljava/lang/String;" -> {
                    val call = instr as? Instruction35c ?: continue
                    val key = cfgStrings[i]?.get(call.registerD) ?: continue
                    contentValuesKeys.add(key)
                }

                // ContentValues.put(key, value) — writing keys
                ref.definingClass == "Landroid/content/ContentValues;" &&
                    ref.name == "put" &&
                    ref.parameterTypes.size == 2 &&
                    ref.parameterTypes[0] == "Ljava/lang/String;" -> {
                    val call = instr as? Instruction35c ?: continue
                    val key = cfgStrings[i]?.get(call.registerD) ?: continue
                    contentValuesKeys.add(key)
                }
            }
        }

        if (contentValuesKeys.isEmpty() && mimeTypes.isEmpty() && operation != "DELETE") return

        val dedupKey = "${classDef.type}:$operation"
        if (dedupKey in seenOps) return
        seenOps.add(dedupKey)

        results.add(
            CrudOperationInfo(
                operation = operation,
                contentValuesKeys = contentValuesKeys.sorted(),
                mimeTypes = mimeTypes.sorted(),
                sourceClass = classDef.type,
                sourceMethod = methodName
            )
        )
    }

    private fun processQuery(
        classDef: DexBackedClassDef,
        methodName: String,
        instructions: List<Instruction>,
        cfgStrings: Array<Map<Int, String>?>
    ) {
        val projectionColumns = mutableSetOf<String>()
        val selectionTemplates = mutableSetOf<String>()
        val sortOrders = mutableSetOf<String>()

        for ((i, instr) in instructions.withIndex()) {
            // Extract projection columns from MatrixCursor.<init>([Ljava/lang/String;...)
            if (instr is ReferenceInstruction) {
                val ref = instr.reference as? MethodReference
                if (ref != null &&
                    ref.definingClass == "Landroid/database/MatrixCursor;" &&
                    ref.name == "<init>" &&
                    ref.parameterTypes.isNotEmpty() &&
                    ref.parameterTypes[0] == "[Ljava/lang/String;"
                ) {
                    // Get the array register — first param after 'this'
                    val arrayReg = when (instr) {
                        is Instruction35c -> instr.registerD
                        else -> null
                    }
                    if (arrayReg != null) {
                        val cols = extractStringArray(instructions, cfgStrings, i, arrayReg)
                        projectionColumns.addAll(cols)
                    }
                }
            }

            // Scan const-string for selection templates and sort orders
            if (instr is ReferenceInstruction) {
                val strRef = instr.reference as? StringReference ?: continue
                val str = strRef.string
                if (str.length > 200) continue

                if (selectionPattern.containsMatchIn(str)) {
                    selectionTemplates.add(str)
                }
                if (sortOrderPattern.matches(str.trim())) {
                    sortOrders.add(str.trim())
                }
            }
        }

        if (projectionColumns.isEmpty() && selectionTemplates.isEmpty() && sortOrders.isEmpty()) return

        val dedupKey = "${classDef.type}:QUERY"
        if (dedupKey in seenOps) return
        seenOps.add(dedupKey)

        results.add(
            CrudOperationInfo(
                operation = "QUERY",
                projectionColumns = projectionColumns.toList(),
                selectionTemplates = selectionTemplates.sorted(),
                sortOrders = sortOrders.sorted(),
                sourceClass = classDef.type,
                sourceMethod = methodName
            )
        )
    }

    private fun processOpenFile(
        classDef: DexBackedClassDef,
        methodName: String,
        instructions: List<Instruction>,
        cfgStrings: Array<Map<Int, String>?>
    ) {
        val modes = mutableSetOf<String>()

        for (instr in instructions) {
            if (instr !is ReferenceInstruction) continue
            val strRef = instr.reference as? StringReference ?: continue
            val str = strRef.string
            if (str in validOpenFileModes) {
                modes.add(str)
            }
        }

        if (modes.isEmpty()) return

        val dedupKey = "${classDef.type}:OPEN_FILE"
        if (dedupKey in seenOps) return
        seenOps.add(dedupKey)

        results.add(
            CrudOperationInfo(
                operation = "OPEN_FILE",
                openFileModes = modes.sorted(),
                sourceClass = classDef.type,
                sourceMethod = methodName
            )
        )
    }

    /**
     * Extracts string array contents from bytecode by walking backward from a target
     * instruction to find the new-array + aput-object pattern used by arrayOf("a", "b", "c").
     *
     * Also handles filled-new-array for small arrays.
     *
     * @param instructions Full instruction list
     * @param cfgStrings CFG string register state at each instruction index
     * @param targetIdx Index of the instruction consuming the array
     * @param arrayReg Register holding the array reference at targetIdx
     * @return List of string values in the array, in order
     */
    private fun extractStringArray(
        instructions: List<Instruction>,
        cfgStrings: Array<Map<Int, String>?>,
        targetIdx: Int,
        arrayReg: Int
    ): List<String> {
        // Walk backward to find new-array or filled-new-array that creates this array
        var newArrayIdx = -1

        for (j in (targetIdx - 1) downTo 0) {
            val prev = instructions[j]

            // Check for filled-new-array {v0, v1, v2}, [Ljava/lang/String;
            // Result is moved to arrayReg via move-result-object
            if (prev.opcode == Opcode.FILLED_NEW_ARRAY && prev is Instruction35c && prev is ReferenceInstruction) {
                val typeRef = prev.reference as? TypeReference
                if (typeRef?.type == "[Ljava/lang/String;") {
                    // Check that the next instruction is move-result-object into arrayReg
                    if (j + 1 < instructions.size) {
                        val moveResult = instructions[j + 1]
                        if (moveResult.opcode == Opcode.MOVE_RESULT_OBJECT &&
                            moveResult is OneRegisterInstruction &&
                            moveResult.registerA == arrayReg
                        ) {
                            return extractFilledNewArrayStrings(prev, cfgStrings, j)
                        }
                    }
                }
            }

            // Check for new-array vArrayReg, vSize, [Ljava/lang/String;
            if (prev.opcode == Opcode.NEW_ARRAY && prev is ReferenceInstruction) {
                val typeRef = prev.reference as? TypeReference
                if (typeRef?.type == "[Ljava/lang/String;" &&
                    prev is OneRegisterInstruction && prev.registerA == arrayReg
                ) {
                    newArrayIdx = j
                    break
                }
            }
        }

        if (newArrayIdx < 0) return emptyList()

        // Collect aput-object instructions between new-array and target.
        // Array elements are always stored sequentially by the compiler,
        // so we use insertion order rather than resolving index registers
        // (which would require crossing branch boundaries in the linear scan).
        val values = mutableListOf<String>()

        for (j in (newArrayIdx + 1) until targetIdx) {
            val instr = instructions[j]
            if (instr.opcode != Opcode.APUT_OBJECT) continue

            val i23x = instr as? com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction23x
                ?: continue

            if (i23x.registerB != arrayReg) continue

            // Use CFG string state at this instruction index
            val state = cfgStrings[j] ?: continue
            val value = state[i23x.registerA] ?: continue

            values.add(value)
        }

        return values
    }

    /**
     * Extract strings from a filled-new-array instruction's registers.
     * Uses CFG string state at the instruction index.
     */
    private fun extractFilledNewArrayStrings(
        instr: Instruction35c,
        cfgStrings: Array<Map<Int, String>?>,
        instrIdx: Int
    ): List<String> {
        val state = cfgStrings[instrIdx] ?: return emptyList()
        val count = instr.registerCount
        val result = mutableListOf<String>()
        val regList = listOf(instr.registerC, instr.registerD, instr.registerE, instr.registerF, instr.registerG)
        for (i in 0 until count.coerceAtMost(5)) {
            val value = state[regList[i]]
            if (value != null) result.add(value)
        }
        return result
    }


    fun getResults(): List<CrudOperationInfo> = results.toList()

    private fun isContentProviderClass(className: String): Boolean {
        var current: String? = className
        var depth = 0
        while (current != null && depth < 10) {
            if (current == "Landroid/content/ContentProvider;") return true
            current = classHierarchy[current]
            depth++
        }
        return false
    }
}
