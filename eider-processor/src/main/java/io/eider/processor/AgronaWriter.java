/*
 * Copyright 2019-2020 Shaun Laurens.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.eider.processor;

import java.io.IOException;
import java.io.Writer;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeSpec;

import org.agrona.MutableDirectBuffer;

public class AgronaWriter implements EiderCodeWriter
{

    public static final String JAVA_NIO_BYTE_ORDER_LITTLE_ENDIAN = "java.nio.ByteOrder.LITTLE_ENDIAN)";
    public static final String JAVA_NIO_BYTE_ORDER_LITTLE_ENDIAN1 = ", java.nio.ByteOrder.LITTLE_ENDIAN)";

    @Override
    public void generate(final ProcessingEnvironment pe, final List<PreprocessedEiderObject> forObjects)
    {
        for (final PreprocessedEiderObject object : forObjects)
        {
            generateFile(pe, object);
        }
    }

    private void generateFile(final ProcessingEnvironment processingEnv, final PreprocessedEiderObject object)
    {
        TypeSpec generated = TypeSpec.classBuilder(object.getName())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addField(buildEiderIdField(processingEnv, object.getSequence()))
            .addFields(offsetsForFields(processingEnv, object.getPropertyList()))
            .addFields(internalFields(processingEnv, object.getPropertyList()))
            .addMethod(buildRead(processingEnv))
            .addMethod(buildWrite(processingEnv))
            .addMethod(buildEiderId(processingEnv))
            .addMethods(forInternalFields(processingEnv, object.getPropertyList()))
            .build();

        JavaFile javaFile = JavaFile.builder(object.getPackageNameGen(), generated)
            .addFileComment("AGRONA FLYWEIGHT GENERATED BY EIDER AT "
                + LocalDateTime.now(ZoneId.of("UTC")).toString()
                + "Z. SPEC: ")
            .addFileComment(object.getClassNameInput())
            .addFileComment(". DO NOT MODIFY")
            .build();

        try
        { // write the file
            JavaFileObject source = processingEnv.getFiler()
                .createSourceFile(object.getPackageNameGen() + "." + object.getName());
            Writer writer = source.openWriter();
            javaFile.writeTo(writer);
            writer.flush();
            writer.close();
        } catch (IOException e)
        {
            // Note: calling e.printStackTrace() will print IO errors
            // that occur from the file already existing after its first run, this is normal
        }
    }

    private Iterable<FieldSpec> internalFields(ProcessingEnvironment processingEnv,
                                               List<PreprocessedEiderProperty> propertyList)
    {
        List<FieldSpec> results = new ArrayList<>();

        results.add(FieldSpec
            .builder(MutableDirectBuffer.class, "buffer")
            .addJavadoc("The internal MutableDirectBuffer")
            .addModifiers(Modifier.PRIVATE)
            .build());

        results.add(FieldSpec
            .builder(int.class, "initialOffset")
            .addJavadoc("The starting offset for reading and writing")
            .addModifiers(Modifier.PRIVATE)
            .build());

        results.add(FieldSpec
            .builder(boolean.class, "FIXED_LENGTH")
            .addJavadoc("Indicates if this flyweight holds a fixed length object")
            .addModifiers(Modifier.STATIC)
            .addModifiers(Modifier.PUBLIC)
            .addModifiers(Modifier.FINAL)
            .initializer(Boolean.toString(true))
            .build());

        return results;

    }

    private Iterable<FieldSpec> offsetsForFields(ProcessingEnvironment processingEnv,
                                                 List<PreprocessedEiderProperty> propertyList)
    {
        List<FieldSpec> results = new ArrayList<>();
        AgronaWriterState runningOffset = new AgronaWriterState();

        results.add(FieldSpec
            .builder(int.class, "HEADER_OFFSET")
            .addJavadoc("The offset for the header")
            .addModifiers(Modifier.STATIC)
            .addModifiers(Modifier.PRIVATE)
            .addModifiers(Modifier.FINAL)
            .initializer(Integer.toString(runningOffset.getCurrentOffset()))
            .build());

        runningOffset.extendCurrentOffset(Integer.BYTES);

        results.add(FieldSpec
            .builder(int.class, "LENGTH_OFFSET")
            .addJavadoc("The length offset. Required for segmented buffers.")
            .addModifiers(Modifier.STATIC)
            .addModifiers(Modifier.PRIVATE)
            .addModifiers(Modifier.FINAL)
            .initializer(Integer.toString(runningOffset.getCurrentOffset()))
            .build());

        runningOffset.extendCurrentOffset(Integer.BYTES);

        for (final PreprocessedEiderProperty property : propertyList)
        {
            results.add(genOffset(processingEnv, property, runningOffset));
        }

        results.add(FieldSpec
            .builder(int.class, "BUFFER_LENGTH")
            .addJavadoc("The total bytes required to store the object")
            .addModifiers(Modifier.STATIC)
            .addModifiers(Modifier.PUBLIC)
            .addModifiers(Modifier.FINAL)
            .initializer(Integer.toString(runningOffset.getCurrentOffset()))
            .build());

        return results;
    }

    private FieldSpec genOffset(ProcessingEnvironment processingEnv,
                                PreprocessedEiderProperty property,
                                AgronaWriterState runningOffset)
    {
        int bytes = byteLength(property.getType(), property.getAnnotations());
        int startAt = runningOffset.getCurrentOffset();
        runningOffset.extendCurrentOffset(bytes);

        return FieldSpec
            .builder(int.class, getOffsetName(property.getName()))
            .addJavadoc("The byte offset in the byte array for this " + property.getType().name()
                + ". Byte length is " + bytes)
            .addModifiers(Modifier.STATIC)
            .addModifiers(Modifier.PRIVATE)
            .addModifiers(Modifier.FINAL)
            .initializer(Integer.toString(startAt))
            .build();

    }

    private String getOffsetName(String name)
    {
        return name.toUpperCase() + "_OFFSET";
    }

    private Iterable<MethodSpec> forInternalFields(final ProcessingEnvironment processingEnv,
                                                   List<PreprocessedEiderProperty> propertyList)
    {
        List<MethodSpec> results = new ArrayList<>();

        results.add(
            MethodSpec.methodBuilder("writeHeader")
                .addJavadoc("Writes the header data to the buffer.")
                .addModifiers(Modifier.PUBLIC)
                .addStatement("buffer.putInt(initialOffset + HEADER_OFFSET"
                    +
                    ", EIDER_SPEC_ID, "
                    + JAVA_NIO_BYTE_ORDER_LITTLE_ENDIAN)
                .addStatement("buffer.putInt(initialOffset + LENGTH_OFFSET"
                    +
                    ", BUFFER_LENGTH, "
                    + JAVA_NIO_BYTE_ORDER_LITTLE_ENDIAN)
                .build()
        );

        results.add(
            MethodSpec.methodBuilder("validateHeader")
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("Validates the length and eiderSpecId in the header "
                    + "against the expected values. False if invalid.")
                .returns(boolean.class)
                .addStatement("final int eiderSpecId = buffer.getInt(initialOffset + HEADER_OFFSET"
                    + JAVA_NIO_BYTE_ORDER_LITTLE_ENDIAN1)
                .addStatement("final int bufferLength = buffer.getInt(initialOffset + LENGTH_OFFSET"
                    + JAVA_NIO_BYTE_ORDER_LITTLE_ENDIAN1)
                .addStatement("if (eiderSpecId != EIDER_SPEC_ID) return false")
                .addStatement("return bufferLength == BUFFER_LENGTH")
                .build()
        );

        for (final PreprocessedEiderProperty property : propertyList)
        {
            results.add(genReadProperty(processingEnv, property));
            results.add(genWriteProperty(processingEnv, property));
        }

        return results;
    }

    private MethodSpec genWriteProperty(ProcessingEnvironment processingEnv, PreprocessedEiderProperty property)
    {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("write" + upperFirst(property.getName()))
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("Writes " + property.getName() + " to the buffer.")
            .addParameter(getInputType(property));

        if (property.getType() == EiderPropertyType.FIXED_STRING)
        {
            builder.addStatement(fixedLengthStringCheck(property));
        }

        builder.addStatement(bufferWrite(processingEnv, property));

        return builder.build();
    }

    private ParameterSpec getInputType(PreprocessedEiderProperty property)
    {
        return ParameterSpec.builder(fromType(property.getType()), "value")
            .addJavadoc("Value for the " + property.getName() + " to write to buffer")
            .build();
    }

    private String fixedLengthStringCheck(PreprocessedEiderProperty property)
    {
        int maxLength = Integer.parseInt(property.getAnnotations().get(Constants.MAXLENGTH));

        return "if (value.length() > " + maxLength + ") throw new RuntimeException(\"Field "
            + property.getName() + " is longer than maxLength=" + maxLength + "\")";
    }

    private String bufferWrite(ProcessingEnvironment processingEnv, PreprocessedEiderProperty property)
    {
        if (property.getType() == EiderPropertyType.INT)
        {
            return "buffer.putInt(initialOffset + " + getOffsetName(property.getName())
                +
                ", value, "
                + JAVA_NIO_BYTE_ORDER_LITTLE_ENDIAN;
        }
        else if (property.getType() == EiderPropertyType.LONG)
        {
            return "buffer.putLong(initialOffset + " + getOffsetName(property.getName())
                +
                ", value, java.nio.ByteOrder.LITTLE_ENDIAN)";
        }
        else if (property.getType() == EiderPropertyType.FIXED_STRING)
        {
            return "buffer.putStringWithoutLengthAscii(initialOffset + " + getOffsetName(property.getName())
                +
                ", value)";
        }
        else if (property.getType() == EiderPropertyType.BOOLEAN)
        {
            return "buffer.putByte(initialOffset + " + getOffsetName(property.getName())
                +
                ", value ? (byte)1 : (byte)0)";
        }
        return "// unsupported type " + property.getType().name();
    }

    private String bufferLimitCheck()
    {
        return "buffer.checkLimit(initialOffset + BUFFER_LENGTH)";
    }

    private MethodSpec genReadProperty(ProcessingEnvironment processingEnv, PreprocessedEiderProperty property)
    {
        return MethodSpec.methodBuilder("read" + upperFirst(property.getName()))
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("Reads " + property.getName() + " as stored in the buffer.")
            .returns(fromType(property.getType()))
            .addStatement(bufferRead(processingEnv, property))
            .build();
    }

    private String bufferRead(ProcessingEnvironment processingEnv, PreprocessedEiderProperty property)
    {
        if (property.getType() == EiderPropertyType.INT)
        {
            return "return buffer.getInt(initialOffset + " + getOffsetName(property.getName())
                +
                JAVA_NIO_BYTE_ORDER_LITTLE_ENDIAN1;
        }
        else if (property.getType() == EiderPropertyType.LONG)
        {
            return "return buffer.getLong(initialOffset + " + getOffsetName(property.getName())
                +
                JAVA_NIO_BYTE_ORDER_LITTLE_ENDIAN1;
        }
        else if (property.getType() == EiderPropertyType.FIXED_STRING)
        {
            int length = Integer.parseInt(property.getAnnotations().get(Constants.MAXLENGTH));
            return "return buffer.getStringWithoutLengthAscii(initialOffset + " + getOffsetName(property.getName())
                +
                ", " + length + ").trim()";
        }
        else if (property.getType() == EiderPropertyType.BOOLEAN)
        {
            return "return buffer.getByte(initialOffset + " + getOffsetName(property.getName())
                +
                ") == (byte)1";
        }
        return "// unsupported type " + property.getType().name();
    }


    private FieldSpec buildEiderIdField(ProcessingEnvironment processingEnv, int sequence)
    {
        return FieldSpec
            .builder(int.class, "EIDER_SPEC_ID")
            .addJavadoc("The eider spec id for this type. Useful in switch statements to detect type from first 32bits")
            .addModifiers(Modifier.STATIC)
            .addModifiers(Modifier.PUBLIC)
            .addModifiers(Modifier.FINAL)
            .initializer(Integer.toString(sequence))
            .build();
    }


    private MethodSpec buildEiderId(ProcessingEnvironment processingEnv)
    {
        return MethodSpec.methodBuilder("eiderId")
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("Returns the eider sequence.\n"
                +
                "@return EIDER_SPEC_ID.\n")
            .returns(int.class)
            .addStatement("return EIDER_SPEC_ID")
            .build();

    }

    private MethodSpec buildRead(ProcessingEnvironment processingEnv)
    {
        return MethodSpec.methodBuilder("setReadBuffer")
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .addJavadoc("Reads from the provided {@link org.agrona.MutableDirectBuffer} from the given offset.\n"
                +
                "@param buffer - buffer to read from.\n"
                +
                "@param offset - offset to begin reading from in the buffer.\n")
            .addParameter(MutableDirectBuffer.class, "buffer")
            .addParameter(int.class, "offset")
            .addStatement("this.initialOffset = offset")
            .addStatement("this.buffer = buffer")
            .addStatement(bufferLimitCheck())
            .build();
    }

    private MethodSpec buildWrite(ProcessingEnvironment processingEnv)
    {
        return MethodSpec.methodBuilder("setWriteBuffer")
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .addJavadoc("Writes to the provided {@link org.agrona.MutableDirectBuffer} from the given offset.\n"
                +
                "@param buffer - buffer to write to.\n"
                +
                "@param offset - offset to begin writing from in the buffer.\n")
            .addParameter(MutableDirectBuffer.class, "buffer")
            .addParameter(int.class, "offset")
            .addStatement("this.initialOffset = offset")
            .addStatement("this.buffer = buffer")
            .addStatement(bufferLimitCheck())
            .build();
    }

    private void writeNote(ProcessingEnvironment pe, String note)
    {
        pe.getMessager().printMessage(Diagnostic.Kind.NOTE, note);
    }

    private String upperFirst(String input)
    {
        return input.substring(0, 1).toUpperCase() + input.substring(1);
    }

    private Class fromType(EiderPropertyType type)
    {
        switch (type)
        {
            case INT:
                return int.class;
            case LONG:
                return long.class;
            case BOOLEAN:
                return boolean.class;
            case CHAR8:
                return char.class;
            case VAR_STRING:
            case FIXED_STRING:
                return String.class;
            default:
                return int.class;
        }
    }

    private int byteLength(EiderPropertyType type, Map<String, String> annotations)
    {
        switch (type)
        {
            case INT:
                return Integer.BYTES;
            case LONG:
                return Long.BYTES;
            case BOOLEAN:
                return 1;
            case CHAR8:
                return Character.BYTES;
            case FIXED_STRING:
                return Integer.parseInt(annotations.get(Constants.MAXLENGTH));
            case VAR_STRING:
                throw new RuntimeException("Agrona writer does not support variable length strings");
            default:
                return Integer.BYTES;
        }
    }

    private String defaultReturnForType(EiderPropertyType type)
    {
        switch (type)
        {
            case INT:
                return "return 0";
            case LONG:
                return "return -1";
            case BOOLEAN:
                return "return false";
            case CHAR8:
                return "return 1";
            case FIXED_STRING:
            case VAR_STRING:
                return "return null";
            default:
                return "return -123";
        }

    }

}
