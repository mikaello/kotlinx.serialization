package kotlinx.serialization.protobuf.generator

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.protobuf.ProtoIntegerType
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoType

@ExperimentalSerializationApi
public fun generateProto(
    descriptors: List<SerialDescriptor>,
    packageName: String? = null,
    options: Map<String, String> = emptyMap()
): String {
    packageName?.let { if (!it.isProtobufFullIdent) throw IllegalArgumentException("Incorrect protobuf package name '$it'") }

    val customTypes = findCustomTypes(descriptors)
    return generateSchemeString(customTypes, packageName, options)
}

private data class CustomTypeDeclaration(val name: String, val descriptor: SerialDescriptor)

private fun findCustomTypes(descriptors: List<SerialDescriptor>): List<CustomTypeDeclaration> {
    val customTypeDescriptorsBySerialName = linkedMapOf<String, SerialDescriptor>()
    descriptors.forEach { addCustomTypeWithElements(it, customTypeDescriptorsBySerialName) }

    val customTypesByName = linkedMapOf<String, CustomTypeDeclaration>()
    customTypeDescriptorsBySerialName.values.forEach {
        val serialNameAsIdent = makeProtobufIdent(it.serialName.substringAfterLast('.', it.serialName))
        var nameVariantNumber = 1
        var name = serialNameAsIdent
        while (customTypesByName.containsKey(name)) {
            nameVariantNumber++
            name = "${serialNameAsIdent}_$nameVariantNumber"
        }
        customTypesByName[name] = CustomTypeDeclaration(name, it)
    }

    return customTypesByName.values.toList()
}

private fun generateSchemeString(
    customTypes: List<CustomTypeDeclaration>,
    packageName: String?,
    options: Map<String, String>
): String {
    val builder = StringBuilder()
    builder.appendLine("""syntax = "proto2";""")
        .appendLine()

    packageName?.let {
        builder.append("package ").append(it).appendLine(';')
    }
    for ((optionName, optionValue) in options) {
        builder.append("option ").append(optionName).append(" = \"").append(optionValue).appendLine("\";")
    }

    for (type in customTypes) {
        builder.appendLine()
        when {
            type.descriptor.isProtobufEnum -> generateEnum(type, builder)
            type.descriptor.isProtobufMessage -> generateMessage(type, builder)
            else -> throw IllegalStateException(
                "Custom type can be enum or message but found kind '${type.descriptor.kind}'"
            )
        }
    }

    return builder.toString()
}

private fun addCustomTypeWithElements(descriptor: SerialDescriptor, all: MutableMap<String, SerialDescriptor>) {
    when {
        descriptor.isProtobufScalar -> return
        descriptor.isProtobufStaticMessage ->
            if (!all.containsKey(descriptor.serialName)) {
                all[descriptor.serialName] = descriptor
                for (childDescriptor in descriptor.elementDescriptors) {
                    addCustomTypeWithElements(childDescriptor, all)
                }
            }
        descriptor.isProtobufEnum -> {
            if (!all.containsKey(descriptor.serialName)) {
                all[descriptor.serialName] = descriptor
            }
        }
        descriptor.isProtobufRepeated -> addCustomTypeWithElements(descriptor.getElementDescriptor(0), all)
        descriptor.isProtobufMap -> addCustomTypeWithElements(descriptor.getElementDescriptor(1), all)
        descriptor.isProtobufSealedMessage -> {
            if (!all.containsKey(descriptor.serialName)) {
                all[descriptor.serialName] = descriptor
            }
            val contextualDescriptor = descriptor.getElementDescriptor(1)
            for (childDescriptor in contextualDescriptor.elementDescriptors) {
                addCustomTypeWithElements(childDescriptor, all)
            }
        }
        descriptor.isProtobufOpenMessage -> {
            if (!all.containsKey(descriptor.serialName)) {
                all[descriptor.serialName] = descriptor
            }
        }
        descriptor.isProtobufContextualMessage -> return
        else -> throw IllegalStateException(
            "Unrecognized custom type with serial name "
                    + "'${descriptor.serialName}' and kind '${descriptor.kind}'"
        )
    }
}

private fun generateMessage(
    message: CustomTypeDeclaration,
    builder: StringBuilder
) {
    val serialDescriptor = message.descriptor

    builder.append("// serial name '").append(removeLineBreaks(serialDescriptor.serialName)).appendLine('\'')

    builder.append("message ").append(message.name).appendLine(" {")
    for (index in 0 until serialDescriptor.elementsCount) {
        val childDescriptor = serialDescriptor.getElementDescriptor(index)
        val annotations = serialDescriptor.getElementAnnotations(index)

        val originFieldName = serialDescriptor.getElementName(index)
        val fieldName = makeProtobufIdent(originFieldName)

        if (originFieldName != fieldName) builder.append("  // original field name '")
            .append(removeLineBreaks(originFieldName))
            .appendLine('\'')

        if (serialDescriptor.isElementOptional(index)) {
            builder.appendLine("  // WARNING: field has a default value that is not present in the scheme")
            println(
                """WARNING: field '$fieldName' in serializable class '${serialDescriptor.serialName}' """ +
                        "has a default value that is not present in the scheme!"
            )
        }

        try {
            when {
                childDescriptor.isProtobufNamedType -> generateNamedType(
                    serialDescriptor,
                    childDescriptor,
                    index,
                    builder
                )
                childDescriptor.isProtobufMap -> generateMapType(childDescriptor, builder)
                childDescriptor.isProtobufRepeated -> generateListType(childDescriptor, builder)
                else -> throw IllegalStateException(
                    "Unprocessed message field type with serial name " +
                            "'${childDescriptor.serialName}' and kind '${childDescriptor.kind}'"
                )
            }
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "An error occurred during value generation for field $fieldName of message ${serialDescriptor.protobufCustomTypeName} " +
                        "(serial name ${serialDescriptor.serialName}): ${e.message}", e
            )
        } catch (e: Exception) {
            throw IllegalStateException(
                "Unexpected error occurred during value generation for field $fieldName of message ${serialDescriptor.protobufCustomTypeName} " +
                        "(serial name ${serialDescriptor.serialName}): ${e.message}", e
            )
        }

        builder.append(' ')
        builder.append(fieldName)
        builder.append(" = ")
        val number = annotations.filterIsInstance<ProtoNumber>().singleOrNull()?.number ?: index + 1
        builder.append(number)
        builder.appendLine(';')
    }
    builder.appendLine('}')
}

private fun generateEnum(
    enum: CustomTypeDeclaration,
    builder: StringBuilder
) {
    builder.append("// serial name '").append(enum.descriptor.serialName).appendLine('\'')
    builder.append("enum ").append(enum.name).appendLine(" {")

    enum.descriptor.elementDescriptors.forEachIndexed { number, element ->
        builder.append("  ").append(element.protobufEnumElementName).append(" = ").append(number).appendLine(';')
    }
    builder.appendLine('}')
}

private fun generateNamedType(
    messageDescriptor: SerialDescriptor,
    fieldDescriptor: SerialDescriptor,
    index: Int,
    builder: StringBuilder
) {
    if (fieldDescriptor.isProtobufContextualMessage) {
        if (messageDescriptor.isProtobufSealedMessage) {
            builder.appendLine("  // decoded as message with one of these types:")
            fieldDescriptor.elementDescriptors.forEachIndexed { _, childDescriptor ->
                builder.append("  //   message ").append(childDescriptor.protobufCustomTypeName)
                    .append(", serial name '").append(removeLineBreaks(childDescriptor.serialName)).appendLine('\'')
            }
        } else {
            builder.appendLine("  // contextual message type")
        }
    }

    builder.append("  ")
        .append(if (messageDescriptor.isElementOptional(index)) "optional " else "required ")
        .append(namedTypeName(fieldDescriptor, messageDescriptor.getElementAnnotations(index)))
}

private fun generateMapType(descriptor: SerialDescriptor, builder: StringBuilder) {
    builder.append("  map<")
    builder.append(protobufMapKeyType(descriptor.getElementDescriptor(0)))
    builder.append(", ")
    builder.append(protobufMapValueType(descriptor.getElementDescriptor(1)))
    builder.append(">")
}

private fun generateListType(descriptor: SerialDescriptor, builder: StringBuilder) {
    builder.append("  repeated ")
    builder.append(protobufRepeatedType(descriptor.getElementDescriptor(0)))
}

private val SerialDescriptor.isProtobufNamedType: Boolean
    get() {
        return isProtobufScalar || isProtobufCustomType
    }

private val SerialDescriptor.isProtobufCustomType: Boolean
    get() {
        return isProtobufMessage || isProtobufEnum
    }

private val SerialDescriptor.isProtobufMessage: Boolean
    get() {
        return isProtobufStaticMessage || isProtobufOpenMessage || isProtobufSealedMessage || isProtobufContextualMessage
    }

private val SerialDescriptor.isProtobufScalar: Boolean
    get() {
        return (kind is PrimitiveKind)
                || (kind is StructureKind.LIST && getElementDescriptor(0).kind === PrimitiveKind.BYTE)
    }

private val SerialDescriptor.isProtobufStaticMessage: Boolean
    get() {
        return kind == StructureKind.CLASS || kind == StructureKind.OBJECT
    }

private val SerialDescriptor.isProtobufOpenMessage: Boolean
    get() {
        return kind == PolymorphicKind.OPEN
    }

private val SerialDescriptor.isProtobufSealedMessage: Boolean
    get() {
        return kind == PolymorphicKind.SEALED
    }

private val SerialDescriptor.isProtobufContextualMessage: Boolean
    get() {
        return kind == SerialKind.CONTEXTUAL
    }

private val SerialDescriptor.isProtobufRepeated: Boolean
    get() {
        return kind == StructureKind.LIST && getElementDescriptor(0).kind != PrimitiveKind.BYTE
    }

private val SerialDescriptor.isProtobufMap: Boolean
    get() {
        return kind == StructureKind.MAP
    }

private val SerialDescriptor.isProtobufEnum: Boolean
    get() {
        return this.kind == SerialKind.ENUM
    }

private val SerialDescriptor.protobufCustomTypeName: String
    get() {
        return makeProtobufIdent(serialName.substringAfterLast('.', serialName))
    }

private val SerialDescriptor.protobufEnumElementName: String
    get() {
        return makeProtobufIdent(serialName.substringAfterLast('.', serialName))
    }

private fun scalarTypeName(descriptor: SerialDescriptor, annotations: List<Annotation> = emptyList()): String {
    val integerType = annotations.filterIsInstance<ProtoType>().firstOrNull()?.type ?: ProtoIntegerType.DEFAULT

    if (descriptor.kind is StructureKind.LIST && descriptor.getElementDescriptor(0).kind == PrimitiveKind.BYTE) {
        return "bytes"
    }

    return when (descriptor.kind as PrimitiveKind) {
        PrimitiveKind.BOOLEAN -> "bool"
        PrimitiveKind.BYTE, PrimitiveKind.CHAR, PrimitiveKind.SHORT, PrimitiveKind.INT ->
            when (integerType) {
                ProtoIntegerType.DEFAULT -> "int32"
                ProtoIntegerType.SIGNED -> "sint32"
                ProtoIntegerType.FIXED -> "fixed32"
            }
        PrimitiveKind.LONG ->
            when (integerType) {
                ProtoIntegerType.DEFAULT -> "int64"
                ProtoIntegerType.SIGNED -> "sint64"
                ProtoIntegerType.FIXED -> "fixed64"
            }
        PrimitiveKind.FLOAT -> "float"
        PrimitiveKind.DOUBLE -> "double"
        PrimitiveKind.STRING -> "string"
    }
}

private fun namedTypeName(descriptor: SerialDescriptor, annotations: List<Annotation>): String {
    return when {
        descriptor.isProtobufScalar -> scalarTypeName(descriptor, annotations)
        descriptor.isProtobufContextualMessage -> "bytes"
        descriptor.isProtobufCustomType -> descriptor.protobufCustomTypeName
        else -> throw IllegalStateException(
            "Descriptor with serial name '${descriptor.serialName}' and kind " +
                    "'${descriptor.kind}' isn't named protobuf type"
        )
    }
}

private fun protobufMapKeyType(descriptor: SerialDescriptor): String {
    if (!descriptor.isProtobufScalar || descriptor.kind === PrimitiveKind.DOUBLE || descriptor.kind === PrimitiveKind.FLOAT) {
        throw IllegalArgumentException(
            "Illegal type for map key: serial name '${descriptor.serialName}' and kind '${descriptor.kind}'." +
                    "As map key type in protobuf allowed only scalar type except for floating point types and bytes."
        )
    }
    return scalarTypeName(descriptor)
}

private fun protobufMapValueType(descriptor: SerialDescriptor): String {
    if (descriptor.isProtobufRepeated) {
        throw IllegalArgumentException("List is not allowed as a map value type in protobuf")
    }
    if (descriptor.isProtobufMap) {
        throw IllegalArgumentException("Map is not allowed as a map value type in protobuf")
    }
    return namedTypeName(descriptor, emptyList())
}

private fun protobufRepeatedType(descriptor: SerialDescriptor): String {
    if (descriptor.isProtobufRepeated) {
        throw IllegalArgumentException("List is not allowed as a list element")
    }
    if (descriptor.isProtobufMap) {
        throw IllegalArgumentException("Map is not allowed as a list element")
    }
    return namedTypeName(descriptor, emptyList())
}

private fun removeLineBreaks(text: String): String {
    return text.replace('\n', ' ').replace('\r', ' ')
}

private val INCORRECT_IDENT_CHAR_REGEX = Regex("[^A-Za-z0-9_]")
private val IDENT_REGEX = Regex("[A-Za-z][A-Za-z0-9_]*")

private fun makeProtobufIdent(serialName: String): String {
    val replaced = serialName.replace(INCORRECT_IDENT_CHAR_REGEX, "_")
    return if (!replaced.matches(IDENT_REGEX)) {
        "a$replaced"
    } else {
        replaced
    }
}

private val String.isProtobufFullIdent: Boolean
    get() {
        split('.').forEach {
            if (!it.matches(IDENT_REGEX)) return false
        }
        return true
    }

