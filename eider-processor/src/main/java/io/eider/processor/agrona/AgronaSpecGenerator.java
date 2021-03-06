package io.eider.processor.agrona;

import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import io.eider.processor.EiderPropertyType;
import io.eider.processor.PreprocessedEiderObject;
import io.eider.processor.PreprocessedEiderProperty;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Int2IntHashMap;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.IntHashSet;
import org.agrona.collections.Object2ObjectHashMap;
import org.agrona.collections.ObjectHashSet;
import org.agrona.concurrent.UnsafeBuffer;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import static io.eider.processor.AttributeConstants.INDEXED;
import static io.eider.processor.AttributeConstants.KEY;
import static io.eider.processor.AttributeConstants.MAXLENGTH;
import static io.eider.processor.AttributeConstants.SEQUENCE_GENERATOR;
import static io.eider.processor.AttributeConstants.UNIQUE;
import static io.eider.processor.agrona.Constants.BUFFER;
import static io.eider.processor.agrona.Constants.BUFFER_LENGTH_1;
import static io.eider.processor.agrona.Constants.CAPACITY;
import static io.eider.processor.agrona.Constants.FALSE;
import static io.eider.processor.agrona.Constants.FIELD;
import static io.eider.processor.agrona.Constants.FLYWEIGHT_SET_UNDERLYING_BUFFER_INTERNAL_BUFFER_OFFSET;
import static io.eider.processor.agrona.Constants.INDEX_DATA_FOR;
import static io.eider.processor.agrona.Constants.INTERNAL_BUFFER;
import static io.eider.processor.agrona.Constants.ITERATOR;
import static io.eider.processor.agrona.Constants.JAVA_NIO_BYTE_ORDER_LITTLE_ENDIAN;
import static io.eider.processor.agrona.Constants.JAVA_NIO_BYTE_ORDER_LITTLE_ENDIAN1;
import static io.eider.processor.agrona.Constants.JAVA_UTIL;
import static io.eider.processor.agrona.Constants.MUTABLE_BUFFER;
import static io.eider.processor.agrona.Constants.NEW_$_T;
import static io.eider.processor.agrona.Constants.OFFSET;
import static io.eider.processor.agrona.Constants.RETURN_FALSE;
import static io.eider.processor.agrona.Constants.RETURN_FLYWEIGHT;
import static io.eider.processor.agrona.Constants.RETURN_NULL;
import static io.eider.processor.agrona.Constants.RETURN_TRUE;
import static io.eider.processor.agrona.Constants.REVERSE_INDEX_DATA_FOR;
import static io.eider.processor.agrona.Constants.THROW_NEW_JAVA_UTIL_NO_SUCH_ELEMENT_EXCEPTION;
import static io.eider.processor.agrona.Constants.TRANSACTION_COPY_BUFFER_SET_FALSE;
import static io.eider.processor.agrona.Constants.TRUE;
import static io.eider.processor.agrona.Constants.UNFILTERED_ITERATOR;
import static io.eider.processor.agrona.Constants.UNSAFE_BUFFER;
import static io.eider.processor.agrona.Constants.WRITE;
import static io.eider.processor.agrona.Util.byteLength;
import static io.eider.processor.agrona.Util.fromType;
import static io.eider.processor.agrona.Util.fromTypeToStr;
import static io.eider.processor.agrona.Util.getBoxedType;
import static io.eider.processor.agrona.Util.getComparator;
import static io.eider.processor.agrona.Util.upperFirst;

public class AgronaSpecGenerator
{

    public void generateSpecRepository(final ProcessingEnvironment pe, final PreprocessedEiderObject object)
    {
        String keyField = getKeyField(object);

        if (keyField == null)
        {
            throw new AgronaWriterException("Repository objects must have exactly one key field");
        }

        TypeSpec.Builder builder = TypeSpec.classBuilder(object.getRepositoryName())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethods(buildRepositoryMethods(object))
            .addFields(buildRepositoryFields(object))
            .addMethods(buildRepositoryIndexMethods(object))
            .addTypes(buildRepositoryIterators(object));

        if (object.isTransactionalRepository())
        {
            builder.addMethods(buildRepositoryTransactionalHelpers(object));
        }

        TypeSpec generated = builder.build();

        JavaFile javaFile = JavaFile.builder(object.getPackageNameGen(), generated)
            .build();

        try
        { // write the file
            JavaFileObject source = pe.getFiler()
                .createSourceFile(object.getPackageNameGen() + "." + object.getRepositoryName());
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

    private Iterable<MethodSpec> buildRepositoryIndexMethods(PreprocessedEiderObject object)
    {
        List<MethodSpec> results = new ArrayList<>();

        for (PreprocessedEiderProperty prop : indexFields(object))
        {
            final String indexName = INDEX_DATA_FOR + upperFirst(prop.getName());
            final String revIndexName = REVERSE_INDEX_DATA_FOR + upperFirst(prop.getName());

            MethodSpec.Builder builder = MethodSpec.methodBuilder("updateIndexFor" + upperFirst(prop.getName()))
                .addJavadoc("Accepts a notification that a flyweight's indexed field has been modified")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(int.class, "offset")
                .addParameter(getBoxedType(prop.getType()), "value")
                .beginControlFlow("if (" + revIndexName + ".containsKey(offset))")
                .addStatement(fromTypeToStr(prop.getType()) + " oldValue = "
                    + revIndexName + ".get(offset)")
                .beginControlFlow("if (!" + revIndexName + ".get(offset)."
                    + getComparator(prop.getType(), "value") + ")")
                .addStatement(indexName + ".get(oldValue).remove(offset)")
                .endControlFlow()
                .endControlFlow()
                .beginControlFlow("if (" + indexName + ".containsKey(value))")
                .addStatement(indexName + ".get(value).add(offset)")
                .nextControlFlow("else")
                .addStatement("final IntHashSet items = new IntHashSet()")
                .addStatement("items.add(offset)")
                .addStatement(indexName + ".put(value, items)")
                .endControlFlow()
                .addStatement(revIndexName + ".put(offset, value)");

            if (isUniqueIndexOnProp(prop))
            {
                final String uniqueIndex = "uniqueIndexFor" + upperFirst(prop.getName());
                builder.addStatement(uniqueIndex + ".add(value)");
            }

            results.add(builder.build());

            final ClassName iterator =
                ClassName.get(List.class);
            final ClassName genObj = ClassName.get(Integer.class);
            final TypeName indexResults = ParameterizedTypeName
                .get(iterator, genObj);

            final ClassName iteratorImpl =
                ClassName.get(ArrayList.class);
            final TypeName indexResultsImpl = ParameterizedTypeName
                .get(iteratorImpl, genObj);

            results.add(
                MethodSpec.methodBuilder("getAllWithIndex" + upperFirst(prop.getName() + "Value"))
                    .addJavadoc("Uses index to return list of offsets matching given value.")
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(getBoxedType(prop.getType()), "value")
                    .returns(indexResults)
                    .addStatement("List<Integer> results = new $T()", indexResultsImpl)
                    .beginControlFlow("if (" + indexName + ".containsKey(value))")
                    .addStatement("results.addAll(" + indexName + ".get(value))")
                    .endControlFlow()
                    .addStatement("return results")
                    .build()
            );

            if (isUniqueIndexOnProp(prop))
            {
                final String uniqueIndex = "uniqueIndexFor" + upperFirst(prop.getName());
                results.add(
                    MethodSpec.methodBuilder("isUnique" + upperFirst(prop.getName() + "Value"))
                        .addJavadoc("Uses unique index to confirm if the repository contains this value or not.")
                        .addModifiers(Modifier.PRIVATE)
                        .addParameter(getBoxedType(prop.getType()), "value")
                        .returns(boolean.class)
                        .addStatement("return !" + uniqueIndex + ".contains(value)")
                        .build()
                );
            }
        }

        return results;
    }

    private Iterable<MethodSpec> buildRepositoryTransactionalHelpers(PreprocessedEiderObject object)
    {
        List<MethodSpec> results = new ArrayList<>();

        MethodSpec.Builder beginTransaction = MethodSpec.methodBuilder("beginTransaction")
            .addJavadoc("Begins the transaction by making a temporary copy of the internal buffer. ")
            .addModifiers(Modifier.PUBLIC)
            .addStatement("internalBuffer.getBytes(0, transactionCopy, 0, repositoryBufferLength)")
            .addStatement("validOffsetsCopy.clear()")
            .addStatement("validOffsetsCopy.addAll(validOffsets)")
            .addStatement("offsetByKeyCopy.clear()")
            .addStatement("offsetByKey.forEach(offsetByKeyCopy::put)")
            .addStatement("currentCountCopy = currentCount")
            .addStatement("transactionCopyBufferSet = true");

        for (PreprocessedEiderProperty prop : indexFields(object))
        {
            final String indexName = INDEX_DATA_FOR + upperFirst(prop.getName());
            final String revIndexName = REVERSE_INDEX_DATA_FOR + upperFirst(prop.getName());
            final String indexNameCopy = INDEX_DATA_FOR + upperFirst(prop.getName() + "Copy");
            final String revIndexNameCopy = REVERSE_INDEX_DATA_FOR + upperFirst(prop.getName() + "Copy");
            //
            beginTransaction.addStatement(indexNameCopy + ".clear()");
            beginTransaction.addStatement(indexNameCopy + ".putAll(" + indexName + ")");
            beginTransaction.addStatement(revIndexNameCopy + ".clear()");
            beginTransaction.addStatement(revIndexNameCopy + ".putAll(" + revIndexName + ")");

            if (isUniqueIndexOnProp(prop))
            {
                final String unqiueIndex = "uniqueIndexFor" + upperFirst(prop.getName());
                final String unqiueIndexCopy = "uniqueIndexCopyFor" + upperFirst(prop.getName());
                beginTransaction.addStatement(unqiueIndexCopy + ".clear()");
                beginTransaction.addStatement(unqiueIndexCopy + ".addAll(" + unqiueIndex + ")");
            }
        }

        results.add(beginTransaction.build());

        results.add(
            MethodSpec.methodBuilder("commit")
                .addJavadoc("Prevents rollback being called within a new call to beginTransaction.")
                .addModifiers(Modifier.PUBLIC)
                .addStatement(TRANSACTION_COPY_BUFFER_SET_FALSE)
                .build()
        );

        MethodSpec.Builder rollback = MethodSpec.methodBuilder("rollback")
            .addJavadoc("Restores the internal buffer to the state it was at when beginTransaction was called. ")
            .addJavadoc("Returns true if rollback was done; false if not. ")
            .addJavadoc("Will not rollback after a commit or before beginTransaction called.")
            .addModifiers(Modifier.PUBLIC)
            .returns(boolean.class)
            .beginControlFlow("if (transactionCopyBufferSet)")
            .addStatement("internalBuffer.putBytes(0, transactionCopy, 0, repositoryBufferLength)")
            .addStatement("validOffsets.clear()")
            .addStatement("validOffsets.addAll(validOffsetsCopy)")
            .addStatement("validOffsetsCopy.clear()")
            .addStatement("offsetByKey.clear()")
            .addStatement("offsetByKeyCopy.forEach(offsetByKey::put)")
            .addStatement("offsetByKeyCopy.clear()")
            .addStatement("currentCount = currentCountCopy")
            .addStatement(TRANSACTION_COPY_BUFFER_SET_FALSE)
            .addStatement("currentCountCopy = 0");

        for (PreprocessedEiderProperty prop : indexFields(object))
        {
            final String indexName = INDEX_DATA_FOR + upperFirst(prop.getName());
            final String revIndexName = REVERSE_INDEX_DATA_FOR + upperFirst(prop.getName());
            final String indexNameCopy = INDEX_DATA_FOR + upperFirst(prop.getName() + "Copy");
            final String revIndexNameCopy = REVERSE_INDEX_DATA_FOR + upperFirst(prop.getName() + "Copy");
            //
            rollback.addStatement(indexName + ".clear()");
            rollback.addStatement(indexName + ".putAll(" + indexNameCopy + ")");
            rollback.addStatement(indexNameCopy + ".clear()");
            rollback.addStatement(revIndexName + ".clear()");
            rollback.addStatement(revIndexName + ".putAll(" + revIndexNameCopy + ")");
            rollback.addStatement(revIndexNameCopy + ".clear()");

            if (isUniqueIndexOnProp(prop))
            {
                final String unqiueIndex = "uniqueIndexFor" + upperFirst(prop.getName());
                final String unqiueIndexCopy = "uniqueIndexCopyFor" + upperFirst(prop.getName());
                beginTransaction.addStatement(unqiueIndex + ".clear()");
                beginTransaction.addStatement(unqiueIndex + ".addAll(" + unqiueIndexCopy + ")");
                beginTransaction.addStatement(unqiueIndexCopy + ".clear()");
            }
        }

        rollback.addStatement(RETURN_TRUE);
        rollback.endControlFlow().addStatement(RETURN_FALSE);
        results.add(rollback.build());

        return results;
    }

    private Iterable<TypeSpec> buildRepositoryIterators(PreprocessedEiderObject object)
    {
        List<TypeSpec> results = new ArrayList<>();

        final ClassName iterator = ClassName.get(JAVA_UTIL, ITERATOR);
        final ClassName genObj = ClassName.get("", object.getName());
        final TypeName iteratorGen = ParameterizedTypeName.get(iterator, genObj);

        TypeSpec allItems = TypeSpec.classBuilder(UNFILTERED_ITERATOR)
            .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
            .addSuperinterface(iteratorGen)
            .addField(FieldSpec.builder(ClassName.get(object.getPackageNameGen(), object.getName()),
                "iteratorFlyweight", Modifier.PRIVATE).initializer("new " + object.getName() + "()").build())
            .addField(FieldSpec.builder(int.class, "currentOffset", Modifier.PRIVATE).initializer("0").build())
            .addMethod(buildAllIteratorHasNext(object))
            .addMethod(buildAllIteratorNext(object))
            .addMethod(buildAllIteratorReset())
            .build();

        results.add(allItems);

        return results;
    }

    private MethodSpec buildAllIteratorReset()
    {
        return MethodSpec.methodBuilder("reset")
            .addModifiers(Modifier.PUBLIC)
            .returns(ClassName.get("", UNFILTERED_ITERATOR))
            .addStatement("currentOffset = 0")
            .addStatement("return this")
            .build();
    }

    private MethodSpec buildAllIteratorNext(PreprocessedEiderObject object)
    {
        return MethodSpec.methodBuilder("next")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(ClassName.get(object.getPackageNameGen(), object.getName()))
            .beginControlFlow("if (hasNext())")
            .beginControlFlow("if (currentOffset > maxUsedOffset)")
            .addStatement(THROW_NEW_JAVA_UTIL_NO_SUCH_ELEMENT_EXCEPTION)
            .endControlFlow()
            .addStatement("iteratorFlyweight.setUnderlyingBuffer(internalBuffer, currentOffset)")
            .addStatement("currentOffset = currentOffset + " + object.getName() + BUFFER_LENGTH_1)
            .addStatement("return iteratorFlyweight")
            .endControlFlow()
            .addStatement(THROW_NEW_JAVA_UTIL_NO_SUCH_ELEMENT_EXCEPTION)
            .build();
    }

    private MethodSpec buildAllIteratorHasNext(PreprocessedEiderObject object)
    {
        return MethodSpec.methodBuilder("hasNext")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(boolean.class)
            .addStatement("return currentCount != 0 && (currentOffset + "
                +
                object.getName()
                +
                ".BUFFER_LENGTH + 1 <=maxUsedOffset)")
            .build();
    }

    private Iterable<MethodSpec> buildRepositoryMethods(PreprocessedEiderObject object)
    {
        List<MethodSpec> results = new ArrayList<>();

        MethodSpec.Builder builder = MethodSpec.constructorBuilder()
            .addJavadoc("constructor")
            .addParameter(
                ParameterSpec.builder(int.class, CAPACITY)
                    .addJavadoc("capacity to build.")
                    .build()
            )
            .addModifiers(Modifier.PRIVATE)
            .addStatement("flyweight = new " + object.getName() + "()")
            .addStatement("appendFlyweight = new " + object.getName() + "()")
            .addStatement("maxCapacity = capacity")
            .addStatement("repositoryBufferLength = (capacity * "
                +
                object.getName() + ".BUFFER_LENGTH) + capacity")
            .addStatement("internalBuffer = new "
                + "UnsafeBuffer(java.nio.ByteBuffer.allocateDirect(repositoryBufferLength + 1))")
            .addStatement("internalBuffer.setMemory(0, repositoryBufferLength, (byte)0)")
            .addStatement("offsetByKey = new Int2IntHashMap(Integer.MIN_VALUE)")
            .addStatement("validOffsets = new IntHashSet()")
            .addStatement("unfilteredIterator = new UnfilteredIterator()");

        if (object.isTransactionalRepository())
        {
            builder.addStatement("offsetByKeyCopy = new Int2IntHashMap(Integer.MIN_VALUE)");
            builder.addStatement("validOffsetsCopy = new IntHashSet()");
            builder.addStatement("transactionCopy = new "
                + "UnsafeBuffer(java.nio.ByteBuffer.allocateDirect(repositoryBufferLength  + 1))");
        }

        for (PreprocessedEiderProperty prop : indexFields(object))
        {
            builder.addStatement("flyweight.setIndexNotifierFor" + upperFirst(prop.getName())
                + "(this::updateIndexFor" + upperFirst(prop.getName()) + ")");

            if (isUniqueIndexOnProp(prop))
            {
                builder.addStatement("flyweight.setIndexUniqueCheckerFor" + upperFirst(prop.getName())
                    + "(this::isUnique" + upperFirst(prop.getName()) + "Value)");
            }
        }

        results.add(builder.build());

        results.add(
            MethodSpec.methodBuilder("createWithCapacity")
                .addJavadoc("Creates a respository holding at most capacity elements.")
                .addModifiers(Modifier.PUBLIC)
                .addModifiers(Modifier.STATIC)
                .addParameter(int.class, CAPACITY)
                .returns(ClassName.get(object.getPackageNameGen(), object.getRepositoryName()))
                .addStatement("return new " + object.getRepositoryName() + "(capacity)")
                .build()
        );

        results.add(
            MethodSpec.methodBuilder("appendWithKey")
                .addJavadoc("Appends an element in the buffer with the provided key. Key cannot be changed. ")
                .addJavadoc("Returns null if new element could not be created or if the key already exists.")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(int.class, "id")
                .returns(ClassName.get(object.getPackageNameGen(), object.getName()))
                .beginControlFlow("if (currentCount >= maxCapacity)")
                .addStatement(RETURN_NULL)
                .endControlFlow()
                .beginControlFlow("if (offsetByKey.containsKey(id))")
                .addStatement(RETURN_NULL)
                .endControlFlow()
                .addStatement("flyweight.setUnderlyingBuffer(internalBuffer, maxUsedOffset)")
                .addStatement("offsetByKey.put(id, maxUsedOffset)")
                .addStatement("validOffsets.add(maxUsedOffset)")
                .addStatement("flyweight.writeHeader()")
                .addStatement("flyweight.write" + upperFirst(getKeyField(object)) + "(id)")
                .addStatement("flyweight.lockKeyId()")
                .addStatement("currentCount += 1")
                .addStatement("maxUsedOffset = maxUsedOffset + " + object.getName() + BUFFER_LENGTH_1)
                .addStatement(RETURN_FLYWEIGHT)
                .build()
        );

        final String readKeyMethod = "appendFlyweight.read" + upperFirst(getKeyField(object));

        MethodSpec.Builder bufferCopy = MethodSpec.methodBuilder("appendByCopyFromBuffer")
            .addJavadoc("Appends an element in the buffer by copying over from source buffer. ")
            .addJavadoc("Returns null if new element could not be created or if the key already exists.")
            .addModifiers(Modifier.PUBLIC)
            .addParameter(DirectBuffer.class, "buffer")
            .addParameter(int.class, "offset")
            .returns(ClassName.get(object.getPackageNameGen(), object.getName()))
            .beginControlFlow("if (currentCount >= maxCapacity)")
            .addStatement(RETURN_NULL)
            .endControlFlow()
            .addStatement("appendFlyweight.setUnderlyingBuffer(buffer, offset)")
            .beginControlFlow("if (offsetByKey.containsKey(" + readKeyMethod + "()))")
            .addStatement(RETURN_NULL)
            .endControlFlow()
            .addStatement("flyweight.setUnderlyingBuffer(internalBuffer, maxUsedOffset)")
            .addStatement("offsetByKey.put(" + readKeyMethod + "(), maxUsedOffset)")
            .addStatement("validOffsets.add(maxUsedOffset)")
            .addStatement("internalBuffer.putBytes(maxUsedOffset, buffer, offset, "
                + object.getName() + ".BUFFER_LENGTH)")
            .addStatement("flyweight.lockKeyId()")
            .addStatement("currentCount += 1");

        if (objectHasIndexedField(object))
        {
            for (PreprocessedEiderProperty prop : object.getPropertyList())
            {
                if (prop.getAnnotations().get(INDEXED).equalsIgnoreCase(TRUE))
                {
                    //need to update the index for each item read.
                    String read = "appendFlyweight.read" + upperFirst(prop.getName()) + "()";
                    String call = "updateIndexFor" + upperFirst(prop.getName() + "(maxUsedOffset, " + read + ")");
                    bufferCopy.addStatement(call);
                }
            }
        }

        bufferCopy.addStatement("maxUsedOffset = maxUsedOffset + " + object.getName() + BUFFER_LENGTH_1);
        bufferCopy.addStatement(RETURN_FLYWEIGHT);
        results.add(bufferCopy.build());

        results.add(
            MethodSpec.methodBuilder("containsKey")
                .addJavadoc("Returns true if the given key is known; false if not.")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(int.class, "id")
                .returns(boolean.class)
                .addStatement("return offsetByKey.containsKey(id)")
                .build()
        );

        results.add(
            MethodSpec.methodBuilder("getCurrentCount")
                .addJavadoc("Returns the number of elements currently in the repository.")
                .addModifiers(Modifier.PUBLIC)
                .returns(int.class)
                .addStatement("return currentCount")
                .build()
        );

        results.add(
            MethodSpec.methodBuilder("getCapacity")
                .addJavadoc("Returns the maximum number of elements that can be stored in the repository.")
                .addModifiers(Modifier.PUBLIC)
                .returns(int.class)
                .addStatement("return maxCapacity")
                .build()
        );

        results.add(
            MethodSpec.methodBuilder("dumpBuffer")
                .addJavadoc("Returns the internal buffer as a byte[]. Warning! Allocates.")
                .addModifiers(Modifier.PRIVATE)
                .returns(ArrayTypeName.of(byte.class))
                .addStatement("byte[] tmpBuffer = new byte[repositoryBufferLength]")
                .addStatement("internalBuffer.getBytes(0, tmpBuffer)")
                .addStatement("return tmpBuffer")
                .build()
        );

        results.add(
            MethodSpec.methodBuilder("getByKey")
                .addJavadoc("Moves the flyweight onto the buffer segment associated with the provided key. ")
                .addJavadoc("Returns null if not found.")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(int.class, "id")
                .returns(ClassName.get(object.getPackageNameGen(), object.getName()))
                .beginControlFlow("if (offsetByKey.containsKey(id))")
                .addStatement("int offset = offsetByKey.get(id)")
                .addStatement(FLYWEIGHT_SET_UNDERLYING_BUFFER_INTERNAL_BUFFER_OFFSET)
                .addStatement("flyweight.lockKeyId()")
                .addStatement(RETURN_FLYWEIGHT)
                .endControlFlow()
                .addStatement(RETURN_NULL)
                .build()
        );

        results.add(
            MethodSpec.methodBuilder("getByBufferIndex")
                .addJavadoc("Moves the flyweight onto the buffer segment for the provided 0-based buffer index. ")
                .addJavadoc("Returns null if not found.")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(int.class, "index")
                .returns(ClassName.get(object.getPackageNameGen(), object.getName()))
                .beginControlFlow("if ((index + 1) <= currentCount)")
                .addStatement("int offset = index + (index * flyweight.BUFFER_LENGTH)")
                .addStatement(FLYWEIGHT_SET_UNDERLYING_BUFFER_INTERNAL_BUFFER_OFFSET)
                .addStatement("flyweight.lockKeyId()")
                .addStatement(RETURN_FLYWEIGHT)
                .endControlFlow()
                .addStatement(RETURN_NULL)
                .build()
        );

        results.add(
            MethodSpec.methodBuilder("getOffsetByBufferIndex")
                .addJavadoc("Returns offset of given 0-based index, or -1 if invalid.")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(int.class, "index")
                .returns(int.class)
                .beginControlFlow("if ((index + 1) <= currentCount)")
                .addStatement("return index + (index * flyweight.BUFFER_LENGTH)")
                .endControlFlow()
                .addStatement("return -1")
                .build()
        );

        results.add(
            MethodSpec.methodBuilder("getByBufferOffset")
                .addJavadoc("Moves the flyweight onto the buffer offset, but only if it is a valid offset. ")
                .addJavadoc("Returns null if the offset is invalid.")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(int.class, "offset")
                .returns(ClassName.get(object.getPackageNameGen(), object.getName()))
                .beginControlFlow("if (validOffsets.contains(offset))")
                .addStatement(FLYWEIGHT_SET_UNDERLYING_BUFFER_INTERNAL_BUFFER_OFFSET)
                .addStatement("flyweight.lockKeyId()")
                .addStatement(RETURN_FLYWEIGHT)
                .endControlFlow()
                .addStatement(RETURN_NULL)
                .build()
        );

        results.add(
            MethodSpec.methodBuilder("getUnderlyingBuffer")
                .addJavadoc("Returns the underlying buffer.")
                .addModifiers(Modifier.PUBLIC)
                .returns(DirectBuffer.class)
                .addStatement("return internalBuffer")
                .build()
        );

        results.add(
            MethodSpec.methodBuilder("getCrc32")
                .addJavadoc("Returns the CRC32 of the underlying buffer. Warning! Allocates.")
                .addModifiers(Modifier.PUBLIC)
                .returns(long.class)
                .addStatement("crc32.reset()")
                .addStatement("crc32.update(dumpBuffer())")
                .addStatement("return crc32.getValue()")
                .build()
        );

        final ClassName iterator = ClassName.get(JAVA_UTIL, ITERATOR);
        final ClassName genObj = ClassName.get("", object.getName());
        final TypeName iteratorGen = ParameterizedTypeName.get(iterator, genObj);

        results.add(
            MethodSpec.methodBuilder("allItems")
                .addJavadoc("Returns iterator which returns all items. ")
                .addModifiers(Modifier.PUBLIC)
                .returns(iteratorGen)
                .addStatement("return unfilteredIterator")
                .build()
        );

        return results;
    }

    private Iterable<FieldSpec> buildRepositoryFields(PreprocessedEiderObject object)
    {
        List<FieldSpec> results = new ArrayList<>();

        results.add(FieldSpec
            .builder(UnsafeBuffer.class, INTERNAL_BUFFER)
            .addJavadoc("The internal MutableDirectBuffer holding capacity instances.")
            .addModifiers(Modifier.FINAL)
            .addModifiers(Modifier.PRIVATE)
            .build());

        results.add(FieldSpec
            .builder(Int2IntHashMap.class, "offsetByKey")
            .addJavadoc("For mapping the key to the offset.")
            .addModifiers(Modifier.FINAL)
            .addModifiers(Modifier.PRIVATE)
            .build());

        results.add(FieldSpec
            .builder(IntHashSet.class, "validOffsets")
            .addJavadoc("Keeps track of valid offsets.")
            .addModifiers(Modifier.FINAL)
            .addModifiers(Modifier.PRIVATE)
            .build());

        results.add(FieldSpec
            .builder(CRC32.class, "crc32")
            .addJavadoc("Used to compute CRC32 of the underlying buffer")
            .addModifiers(Modifier.FINAL)
            .addModifiers(Modifier.PRIVATE)
            .initializer("new CRC32()")
            .build());

        results.add(FieldSpec
            .builder(int.class, "maxUsedOffset")
            .addJavadoc("The current max offset used of the buffer.")
            .initializer("0")
            .addModifiers(Modifier.PRIVATE)
            .build());

        results.add(FieldSpec
            .builder(int.class, "currentCount")
            .addJavadoc("The current count of elements in the buffer.")
            .addModifiers(Modifier.PRIVATE)
            .initializer("0")
            .build());

        results.add(FieldSpec
            .builder(int.class, "maxCapacity")
            .addJavadoc("The maximum count of elements in the buffer.")
            .addModifiers(Modifier.PRIVATE)
            .addModifiers(Modifier.FINAL)
            .build());

        results.add(FieldSpec
            .builder(ClassName.get("", UNFILTERED_ITERATOR), "unfilteredIterator")
            .addJavadoc("The iterator for unfiltered items.")
            .addModifiers(Modifier.PRIVATE)
            .addModifiers(Modifier.FINAL)
            .build());

        results.add(FieldSpec
            .builder(int.class, "repositoryBufferLength")
            .addJavadoc("The length of the internal buffer.")
            .addModifiers(Modifier.PRIVATE)
            .addModifiers(Modifier.FINAL)
            .build());

        results.add(FieldSpec.builder(ClassName.get(object.getPackageNameGen(), object.getName()), "flyweight")
            .addJavadoc("The flyweight used by the repository.")
            .initializer("null")
            .addModifiers(Modifier.PRIVATE)
            .build());

        results.add(FieldSpec.builder(ClassName.get(object.getPackageNameGen(), object.getName()),
            "appendFlyweight")
            .addJavadoc("The flyweight used by the repository for reads during append from buffer operations.")
            .initializer("null")
            .addModifiers(Modifier.PRIVATE)
            .build());

        for (PreprocessedEiderProperty prop : indexFields(object))
        {
            //By Value Index
            final ClassName itemList =
                ClassName.get(IntHashSet.class);

            final ClassName topLevelMap =
                ClassName.get(Object2ObjectHashMap.class);
            final ClassName genObj = ClassName.get(getBoxedType(prop.getType()));
            final TypeName indexDataMap = ParameterizedTypeName
                .get(topLevelMap, genObj, itemList);

            results.add(FieldSpec.builder(indexDataMap, INDEX_DATA_FOR + upperFirst(prop.getName()))
                .addJavadoc("Holds the index data for the " + prop.getName() + FIELD)
                .initializer(NEW_$_T, indexDataMap)
                .addModifiers(Modifier.PRIVATE)
                .build());

            if (isUniqueIndexOnProp(prop))
            {
                final ClassName topLevelUniqueSet =
                    ClassName.get(ObjectHashSet.class);
                final TypeName indexDataSet = ParameterizedTypeName
                    .get(topLevelUniqueSet, genObj);

                results.add(FieldSpec.builder(indexDataSet, "uniqueIndexFor" + upperFirst(prop.getName()))
                    .addJavadoc("Holds the unique index data for the " + prop.getName() + FIELD)
                    .initializer(NEW_$_T, indexDataSet)
                    .addModifiers(Modifier.PRIVATE)
                    .build());

                if (object.isTransactional())
                {
                    results.add(FieldSpec.builder(indexDataSet, "uniqueIndexCopyFor" + upperFirst(prop.getName()))
                        .addJavadoc("Holds the transactional copy index data for the " + prop.getName() + FIELD)
                        .initializer(NEW_$_T, indexDataSet)
                        .addModifiers(Modifier.PRIVATE)
                        .build());
                }
            }

            if (object.isTransactional())
            {
                results.add(FieldSpec.builder(indexDataMap, INDEX_DATA_FOR + upperFirst(prop.getName())
                    + "Copy")
                    .addJavadoc("Holds the transactional copy index data for the " + prop.getName() + FIELD)
                    .initializer(NEW_$_T, indexDataMap)
                    .addModifiers(Modifier.PRIVATE)
                    .build());
            }

            final ClassName reverseMap =
                ClassName.get(Int2ObjectHashMap.class);
            final TypeName reversedIndex = ParameterizedTypeName
                .get(reverseMap, genObj);

            results.add(FieldSpec.builder(reversedIndex, REVERSE_INDEX_DATA_FOR + upperFirst(prop.getName()))
                .addJavadoc("Holds the reverse index data for the " + prop.getName() + FIELD)
                .initializer(NEW_$_T, reversedIndex)
                .addModifiers(Modifier.PRIVATE)
                .build());

            if (object.isTransactional())
            {
                results.add(FieldSpec.builder(reversedIndex, REVERSE_INDEX_DATA_FOR
                    + upperFirst(prop.getName()) + "Copy")
                    .addJavadoc("Holds the reverse index data for the " + prop.getName() + FIELD)
                    .initializer(NEW_$_T, reversedIndex)
                    .addModifiers(Modifier.PRIVATE)
                    .build());
            }
        }

        if (object.isTransactionalRepository())
        {
            results.add(FieldSpec
                .builder(Int2IntHashMap.class, "offsetByKeyCopy")
                .addJavadoc("The offsets by key at time of beginTransaction.")
                .addModifiers(Modifier.PRIVATE)
                .initializer("null")
                .build());

            results.add(FieldSpec
                .builder(boolean.class, "transactionCopyBufferSet")
                .addJavadoc("Flag which defines if the transaction copy buffer is set.")
                .addModifiers(Modifier.PRIVATE)
                .initializer(FALSE)
                .build());

            results.add(FieldSpec
                .builder(int.class, "currentCountCopy")
                .addJavadoc("The current count of elements in the buffer.")
                .addModifiers(Modifier.PRIVATE)
                .initializer("0")
                .build());

            results.add(FieldSpec
                .builder(UnsafeBuffer.class, "transactionCopy")
                .addJavadoc("The MutableDirectBuffer used internally for rollbacks.")
                .addModifiers(Modifier.PRIVATE)
                .build());

            results.add(FieldSpec
                .builder(int.class, "transactionCopyLength")
                .addJavadoc("The current length of the transactionCopy buffer.")
                .addModifiers(Modifier.PRIVATE)
                .initializer("0")
                .build());

            results.add(FieldSpec
                .builder(IntHashSet.class, "validOffsetsCopy")
                .addJavadoc("Transactional copy of valid offsets set.")
                .addModifiers(Modifier.FINAL)
                .addModifiers(Modifier.PRIVATE)
                .build());
        }

        return results;
    }

    private String getKeyField(PreprocessedEiderObject object)
    {
        for (final PreprocessedEiderProperty property : object.getPropertyList())
        {
            if (property.getAnnotations().get(KEY).equalsIgnoreCase(TRUE))
            {
                return property.getName();
            }
        }
        return null;
    }

    public void generateSpecObject(final ProcessingEnvironment processingEnv, final PreprocessedEiderObject object,
                                   final AgronaWriterState state, final AgronaWriterGlobalState globalState)
    {
        TypeSpec.Builder builder = TypeSpec.classBuilder(object.getName())
            .addModifiers(Modifier.PUBLIC)
            .addField(buildEiderIdField(object.getEiderId()))
            .addField(buildEiderGroupIdField(object.getEiderGroupId()))
            .addFields(offsetsForFields(object, state, globalState))
            .addFields(internalFields(object))
            .addMethod(buildSetUnderlyingBuffer(object))
            .addMethod(buildSetUnderlyingBufferAndWriteHeader())
            .addMethod(buildEiderId())
            .addMethods(forInternalFields(object));

        if (object.isTransactional())
        {
            builder.addMethods(buildTransactionHelpers());
        }
        else
        {
            builder.addMethods(buildNonTransactionHelpers());
        }

        TypeSpec generated = builder.build();

        JavaFile javaFile = JavaFile.builder(object.getPackageNameGen(), generated)
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

    private Iterable<MethodSpec> buildNonTransactionHelpers()
    {
        List<MethodSpec> results = new ArrayList<>();

        results.add(
            MethodSpec.methodBuilder("supportsTransactions")
                .addJavadoc("True if transactions are supported; false if not.")
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addStatement(RETURN_FALSE)
                .build()
        );

        return results;
    }

    private Iterable<MethodSpec> buildTransactionHelpers()
    {
        List<MethodSpec> results = new ArrayList<>();

        results.add(
            MethodSpec.methodBuilder("supportsTransactions")
                .addJavadoc("True if transactions are supported; false if not.")
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addStatement(RETURN_TRUE)
                .build()
        );

        results.add(
            MethodSpec.methodBuilder("beginTransaction")
                .addJavadoc("Begins the transaction by making a temporary copy of the internal buffer.")
                .addModifiers(Modifier.PUBLIC)
                .addStatement("buffer.getBytes(0, transactionCopy, 0, BUFFER_LENGTH)")
                .addStatement("transactionCopyBufferSet = true")
                .build()
        );

        results.add(
            MethodSpec.methodBuilder("commit")
                .addJavadoc("Prevents rollback being called within a new call to beginTransaction.")
                .addModifiers(Modifier.PUBLIC)
                .addStatement(TRANSACTION_COPY_BUFFER_SET_FALSE)
                .build()
        );

        results.add(
            MethodSpec.methodBuilder("rollback")
                .addJavadoc("Restores the internal buffer to the state it was at when beginTransaction was called. ")
                .addJavadoc("Returns true if rollback was done; false if not. ")
                .addJavadoc("Will not rollback after a commit or before beginTransaction called.")
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .beginControlFlow("if (transactionCopyBufferSet)")
                .addStatement("if (!isMutable) throw new RuntimeException(\"cannot write to immutable buffer\")")
                .addStatement("mutableBuffer.putBytes(0, transactionCopy, 0, BUFFER_LENGTH)")
                .addStatement(TRANSACTION_COPY_BUFFER_SET_FALSE)
                .addStatement(RETURN_TRUE)
                .endControlFlow()
                .addStatement(RETURN_FALSE)
                .build()
        );

        return results;
    }

    private Iterable<FieldSpec> internalFields(PreprocessedEiderObject object)
    {
        List<FieldSpec> results = new ArrayList<>();

        results.add(FieldSpec
            .builder(DirectBuffer.class, BUFFER)
            .addJavadoc("The internal DirectBuffer.")
            .addModifiers(Modifier.PRIVATE)
            .initializer("null")
            .build());

        results.add(FieldSpec
            .builder(MutableDirectBuffer.class, MUTABLE_BUFFER)
            .addJavadoc("The internal DirectBuffer used for mutatation opertions. "
                +
                "Valid only if a mutable buffer was provided.")
            .addModifiers(Modifier.PRIVATE)
            .initializer("null")
            .build());

        results.add(FieldSpec
            .builder(UnsafeBuffer.class, UNSAFE_BUFFER)
            .addJavadoc("The internal UnsafeBuffer. Valid only if an unsafe buffer was provided.")
            .addModifiers(Modifier.PRIVATE)
            .initializer("null")
            .build());

        for (PreprocessedEiderProperty prop : indexFields(object))
        {
            final ClassName indexNotifier =
                ClassName.get("io.eider.util", "IndexUpdateConsumer");
            final ClassName fieldName = ClassName.get(getBoxedType(prop.getType()));
            final TypeName indexUpdateNotifier = ParameterizedTypeName
                .get(indexNotifier, fieldName);

            results.add(FieldSpec
                .builder(indexUpdateNotifier, "indexUpdateNotifier" + upperFirst(prop.getName()))
                .addJavadoc("The consumer notified of indexed field updates. Used to maintain indexes.")
                .addModifiers(Modifier.PRIVATE)
                .initializer("null")
                .build());

            final ClassName uniqueIndexChecker =
                ClassName.get("io.eider.util", "IndexUniquenessConsumer");
            final TypeName indexUniquenessChecker = ParameterizedTypeName
                .get(uniqueIndexChecker, fieldName);

            if (isUniqueIndexOnProp(prop))
            {
                results.add(FieldSpec
                    .builder(indexUniquenessChecker, "indexUniquenessChecker" + upperFirst(prop.getName()))
                    .addJavadoc("The used to confirm the uniqueness of an index value.")
                    .addModifiers(Modifier.PRIVATE)
                    .initializer("null")
                    .build());
            }
        }

        results.add(FieldSpec
            .builder(int.class, "initialOffset")
            .addJavadoc("The starting offset for reading and writing.")
            .addModifiers(Modifier.PRIVATE)
            .build());

        results.add(FieldSpec
            .builder(boolean.class, "isMutable")
            .addJavadoc("Flag indicating if the buffer is mutable.")
            .addModifiers(Modifier.PRIVATE)
            .initializer(FALSE)
            .build());

        results.add(FieldSpec
            .builder(boolean.class, "isUnsafe")
            .addJavadoc("Flag indicating if the buffer is an UnsafeBuffer.")
            .addModifiers(Modifier.PRIVATE)
            .initializer(FALSE)
            .build());

        results.add(FieldSpec
            .builder(boolean.class, "FIXED_LENGTH")
            .addJavadoc("Indicates if this flyweight holds a fixed length object.")
            .addModifiers(Modifier.STATIC)
            .addModifiers(Modifier.PUBLIC)
            .addModifiers(Modifier.FINAL)
            .initializer(Boolean.toString(true))
            .build());

        if (object.isTransactional())
        {
            results.add(FieldSpec
                .builder(boolean.class, "transactionCopyBufferSet")
                .addJavadoc("Flag which defines if the transaction copy buffer is set.")
                .addModifiers(Modifier.PRIVATE)
                .initializer(FALSE)
                .build());

            results.add(FieldSpec
                .builder(UnsafeBuffer.class, "transactionCopy")
                .addJavadoc("The MutableDirectBuffer used internally for rollbacks.")
                .initializer("new UnsafeBuffer(java.nio.ByteBuffer.allocateDirect(BUFFER_LENGTH + 1))")
                .addModifiers(Modifier.PRIVATE)
                .build());
        }

        if (containsKeyField(object))
        {
            results.add(FieldSpec
                .builder(boolean.class, "keyLocked")
                .addJavadoc("Internal field to support the lockKey method.")
                .initializer(FALSE)
                .addModifiers(Modifier.PRIVATE)
                .build());
        }

        return results;
    }

    private boolean objectHasIndexedField(PreprocessedEiderObject object)
    {
        for (final PreprocessedEiderProperty property : object.getPropertyList())
        {
            if (property.getAnnotations() != null && property.getAnnotations().containsKey(INDEXED)
                && property.getAnnotations().get(INDEXED).equalsIgnoreCase(TRUE))
            {
                return true;
            }
        }
        return false;
    }

    private boolean containsKeyField(PreprocessedEiderObject object)
    {
        for (final PreprocessedEiderProperty property : object.getPropertyList())
        {
            if (property.getAnnotations() != null && property.getAnnotations().containsKey(KEY)
                && property.getAnnotations().get(KEY).equalsIgnoreCase(TRUE))
            {
                return true;
            }
        }
        return false;
    }

    private Iterable<FieldSpec> offsetsForFields(PreprocessedEiderObject object,
                                                 AgronaWriterState state,
                                                 AgronaWriterGlobalState globalState)
    {
        List<FieldSpec> results = new ArrayList<>();

        results.add(FieldSpec
            .builder(int.class, "HEADER_OFFSET")
            .addJavadoc("The offset for the EIDER_ID within the buffer.")
            .addModifiers(Modifier.STATIC)
            .addModifiers(Modifier.PRIVATE)
            .addModifiers(Modifier.FINAL)
            .initializer(Integer.toString(state.getCurrentOffset()))
            .build());

        state.extendCurrentOffset(Short.BYTES);

        results.add(FieldSpec
            .builder(int.class, "HEADER_GROUP_OFFSET")
            .addJavadoc("The offset for the EIDER_GROUP_IP within the buffer.")
            .addModifiers(Modifier.STATIC)
            .addModifiers(Modifier.PRIVATE)
            .addModifiers(Modifier.FINAL)
            .initializer(Integer.toString(state.getCurrentOffset()))
            .build());

        state.extendCurrentOffset(Short.BYTES);

        results.add(FieldSpec
            .builder(int.class, "LENGTH_OFFSET")
            .addJavadoc("The length offset. Required for segmented buffers.")
            .addModifiers(Modifier.STATIC)
            .addModifiers(Modifier.PRIVATE)
            .addModifiers(Modifier.FINAL)
            .initializer(Integer.toString(state.getCurrentOffset()))
            .build());

        state.extendCurrentOffset(Integer.BYTES);

        for (final PreprocessedEiderProperty property : object.getPropertyList())
        {
            results.add(genOffset(property, state));
        }

        results.add(FieldSpec
            .builder(int.class, "BUFFER_LENGTH")
            .addJavadoc("The total bytes required to store the object.")
            .addModifiers(Modifier.STATIC)
            .addModifiers(Modifier.PUBLIC)
            .addModifiers(Modifier.FINAL)
            .initializer(Integer.toString(state.getCurrentOffset()))
            .build());

        globalState.getBufferLengths().put(object.getName(), state.getCurrentOffset());

        return results;
    }

    private FieldSpec genOffset(PreprocessedEiderProperty property,
                                AgronaWriterState runningOffset)
    {
        int bytes = byteLength(property.getType(), property.getAnnotations());
        int startAt = runningOffset.getCurrentOffset();
        runningOffset.extendCurrentOffset(bytes);

        return FieldSpec
            .builder(int.class, getOffsetName(property.getName()))
            .addJavadoc("The byte offset in the byte array for this " + property.getType().name()
                + ". Byte length is " + bytes + ".")
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

    @SuppressWarnings("all")
    private Iterable<MethodSpec> forInternalFields(PreprocessedEiderObject object)
    {
        List<PreprocessedEiderProperty> propertyList = object.getPropertyList();
        List<MethodSpec> results = new ArrayList<>();

        results.add(
            MethodSpec.methodBuilder("writeHeader")
                .addJavadoc("Writes the header data to the buffer.")
                .addModifiers(Modifier.PUBLIC)
                .addStatement("if (!isMutable) throw new RuntimeException(\"cannot write to immutable buffer\")")
                .addStatement("mutableBuffer.putShort(initialOffset + HEADER_OFFSET"
                    +
                    ", EIDER_ID, "
                    + JAVA_NIO_BYTE_ORDER_LITTLE_ENDIAN)
                .addStatement("mutableBuffer.putShort(initialOffset + HEADER_GROUP_OFFSET"
                    +
                    ", EIDER_GROUP_ID, "
                    + JAVA_NIO_BYTE_ORDER_LITTLE_ENDIAN)
                .addStatement("mutableBuffer.putInt(initialOffset + LENGTH_OFFSET"
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
                .addStatement("final short eiderId = buffer.getShort(initialOffset + HEADER_OFFSET"
                    + JAVA_NIO_BYTE_ORDER_LITTLE_ENDIAN1)
                .addStatement("final short eiderGroupId = buffer.getShort(initialOffset + HEADER_GROUP_OFFSET"
                    + JAVA_NIO_BYTE_ORDER_LITTLE_ENDIAN1)
                .addStatement("final int bufferLength = buffer.getInt(initialOffset + LENGTH_OFFSET"
                    + JAVA_NIO_BYTE_ORDER_LITTLE_ENDIAN1)
                .addStatement("if (eiderId != EIDER_ID) return false")
                .addStatement("if (eiderGroupId != EIDER_GROUP_ID) return false")
                .addStatement("return bufferLength == BUFFER_LENGTH")
                .build()
        );

        for (PreprocessedEiderProperty prop : indexFields(object))
        {
            final ClassName iterator =
                ClassName.get("io.eider.util", "IndexUpdateConsumer");
            final ClassName fieldName = ClassName.get(getBoxedType(prop.getType()));
            final TypeName indexUpdateNotifier = ParameterizedTypeName
                .get(iterator, fieldName);

            results.add(
                MethodSpec.methodBuilder("setIndexNotifierFor" + upperFirst(prop.getName()))
                    .addModifiers(Modifier.PUBLIC)
                    .addJavadoc("Sets the indexed field update notifier to provided consumer.")
                    .addParameter(indexUpdateNotifier, "indexedNotifier")
                    .addStatement("this.indexUpdateNotifier" + upperFirst(prop.getName())
                        + " = indexedNotifier")
                    .build()
            );

            if (isUniqueIndexOnProp(prop))
            {
                final ClassName uniqueIndexChecker =
                    ClassName.get("io.eider.util", "IndexUniquenessConsumer");
                final TypeName indexUniquenessChecker = ParameterizedTypeName
                    .get(uniqueIndexChecker, fieldName);

                results.add(
                    MethodSpec.methodBuilder("setIndexUniqueCheckerFor" + upperFirst(prop.getName()))
                        .addModifiers(Modifier.PUBLIC)
                        .addJavadoc("Sets the indexed field checker to provided method.")
                        .addParameter(indexUniquenessChecker, "indexChecker")
                        .addStatement("this.indexUniquenessChecker" + upperFirst(prop.getName())
                            + " = indexChecker")
                        .build()
                );
            }
        }

        for (final PreprocessedEiderProperty property : propertyList)
        {
            results.add(genReadProperty(property));
            if (!property.getAnnotations().get(SEQUENCE_GENERATOR).equalsIgnoreCase(TRUE))
            {
                results.add(genWriteProperty(property));
                if (property.getType() == EiderPropertyType.FIXED_STRING)
                {
                    results.add(genWritePropertyWithPadding(property));
                }
            }
            if (property.getAnnotations() != null)
            {
                if (property.getAnnotations().get(KEY).equalsIgnoreCase(TRUE))
                {
                    results.add(getKeyLock(property));
                }
                if (property.getAnnotations().get(SEQUENCE_GENERATOR).equalsIgnoreCase(TRUE))
                {
                    results.add(buildInternalBufferAllocator(object));
                    results.add(buildSequenceGenerator(property));
                    results.add(buildSequenceInitialize(property));
                }
            }
        }

        return results;
    }

    private MethodSpec buildInternalBufferAllocator(PreprocessedEiderObject object)
    {
        final ClassName genObj = ClassName.get("", object.getName());
        final TypeName typeEab = TypeName.get(UnsafeBuffer.class);
        final String objectName = object.getName();

        MethodSpec.Builder builder = MethodSpec.methodBuilder("INSTANCE")
            .addModifiers(Modifier.PUBLIC)
            .addModifiers(Modifier.STATIC)
            .returns(genObj)
            .addJavadoc("Constructs an instance of this object with an internally allocated buffer.");

        builder.addStatement("final UnsafeBuffer buffer = new $T(java.nio.ByteBuffer."
            + "allocateDirect(BUFFER_LENGTH))", typeEab);
        builder.addStatement("final " + objectName + " instance = new " + objectName + "()");
        builder.addStatement("instance.setBufferWriteHeader(buffer, 0)");

        for (final PreprocessedEiderProperty property : object.getPropertyList())
        {
            builder.addStatement("instance.initialize" + upperFirst(property.getName() + "(1)"));
        }

        builder.addStatement("return instance");
        return builder.build();
    }

    private MethodSpec genWritePropertyWithPadding(PreprocessedEiderProperty property)
    {
        MethodSpec.Builder builder = MethodSpec.methodBuilder(WRITE + upperFirst(property.getName()
            + "WithPadding"))
            .addModifiers(Modifier.PUBLIC)
            .returns(boolean.class)
            .addJavadoc("Writes " + property.getName() + " to the buffer with padding. ")
            .addParameter(getInputType(property));

        final String underlying = WRITE + upperFirst(property.getName());
        int maxLength = Integer.parseInt(property.getAnnotations().get(MAXLENGTH));
        builder.addStatement("final String padded = String.format(\"%" + maxLength + "s\", value)");
        builder.addStatement("return " + underlying + "(padded)");
        return builder.build();
    }

    private MethodSpec buildSequenceInitialize(PreprocessedEiderProperty property)
    {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("initialize" + upperFirst(property.getName()))
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("Initializes " + property.getName() + " to the provided value. ")
            .addParameter(getInputType(property));

        if (property.getAnnotations() != null && property.getAnnotations().get(KEY).equalsIgnoreCase(TRUE))
        {
            builder.addStatement("if (keyLocked) throw new RuntimeException(\"Cannot write key after locking\")");
            builder.addJavadoc("This field is marked key=true.");
        }

        builder.addStatement(bufferWrite(property));
        return builder.build();
    }

    private MethodSpec buildSequenceGenerator(PreprocessedEiderProperty property)
    {
        final String read = "read" + upperFirst(property.getName());
        final String offset = property.getName().toUpperCase() + "_OFFSET";
        final String init = "initialize" + upperFirst(property.getName());

        MethodSpec.Builder builder = MethodSpec.methodBuilder("next" + upperFirst(property.getName())
            +
            "Sequence")
            .addModifiers(Modifier.PUBLIC)
            .returns(fromType(property.getType()))
            .addJavadoc("Increments and returns the sequence in field " + property.getName() + ".")
            .beginControlFlow("if (isUnsafe)")
            .addStatement("final " + fromTypeToStr(property.getType()) + " currentVal = "
                + "unsafeBuffer.getAndAddInt(initialOffset + " + offset + ", 1)")
            .addStatement("return currentVal")
            .endControlFlow()
            .addStatement("final " + fromTypeToStr(property.getType()) + " safeCurrentVal = " + read + "()")
            .addStatement(init + "(safeCurrentVal + 1)")
            .addStatement("return " + read + "()");

        return builder.build();
    }

    private MethodSpec getKeyLock(PreprocessedEiderProperty property)
    {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("lockKey" + upperFirst(property.getName()))
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("Prevents any further updates to the key field.")
            .addStatement("keyLocked = true");

        return builder.build();
    }

    private MethodSpec genWriteProperty(PreprocessedEiderProperty property)
    {
        MethodSpec.Builder builder = MethodSpec.methodBuilder(WRITE + upperFirst(property.getName()))
            .addModifiers(Modifier.PUBLIC)
            .returns(boolean.class)
            .addJavadoc("Writes " + property.getName() + " to the buffer. Returns true if success, false if not.")
            .addParameter(getInputType(property));

        builder.addStatement("if (!isMutable) throw new RuntimeException(\"Cannot write to immutable buffer\")");

        if (property.getType() == EiderPropertyType.FIXED_STRING)
        {
            int maxLength = Integer.parseInt(property.getAnnotations().get(MAXLENGTH));
            builder.addStatement(fixedLengthStringCheck(property, maxLength));
        }

        if (property.getAnnotations() != null && property.getAnnotations().get(KEY).equalsIgnoreCase(TRUE))
        {
            builder.addStatement("if (keyLocked) throw new RuntimeException(\"Cannot write key after locking\")");
            builder.addJavadoc("This field is marked key=true.");
        }

        if (property.getAnnotations() != null && property.getAnnotations().get(INDEXED).equalsIgnoreCase(TRUE))
        {

            if (property.getAnnotations().get(UNIQUE).equalsIgnoreCase(TRUE))
            {
                //this.indexUniquenessChecker
                builder.beginControlFlow("if (indexUniquenessChecker" + upperFirst(property.getName()) + " != null)");
                final String condition = "indexUniquenessChecker" + upperFirst(property.getName())
                    + ".isUnique(value)";
                builder.beginControlFlow("if (!" + condition + ")");
                builder.addStatement(RETURN_FALSE);
                builder.endControlFlow();
                builder.endControlFlow();

                builder.addJavadoc(" Uniquely indexed field. ");
            }
            else
            {
                builder.addJavadoc(" Indexed field. ");
            }

            builder.beginControlFlow("if (indexUpdateNotifier" + upperFirst(property.getName()) + " != null)");
            builder.addStatement("indexUpdateNotifier" + upperFirst(property.getName())
                + ".accept(initialOffset, value)");
            builder.endControlFlow();
        }

        if (property.getType() == EiderPropertyType.FIXED_STRING)
        {
            builder.addJavadoc("Warning! Does not pad the string.");
            builder.addStatement("mutableBuffer.putStringWithoutLengthAscii(initialOffset + "
                + getOffsetName(property.getName()) + ", value)");
        }
        else
        {
            builder.addStatement(bufferWrite(property));
        }
        builder.addStatement(RETURN_TRUE);
        return builder.build();
    }

    private ParameterSpec getInputType(PreprocessedEiderProperty property)
    {
        return ParameterSpec.builder(fromType(property.getType()), "value")
            .addJavadoc("Value for the " + property.getName() + " to write to buffer.")
            .build();
    }

    private String fixedLengthStringCheck(PreprocessedEiderProperty property, int maxLength)
    {
        return "if (value.length() > " + maxLength + ") throw new RuntimeException(\"Field "
            + property.getName() + " is longer than maxLength=" + maxLength + "\")";
    }

    private String bufferWrite(PreprocessedEiderProperty property)
    {
        if (property.getType() == EiderPropertyType.INT)
        {
            return "mutableBuffer.putInt(initialOffset + " + getOffsetName(property.getName())
                +
                ", value, "
                + JAVA_NIO_BYTE_ORDER_LITTLE_ENDIAN;
        }
        else if (property.getType() == EiderPropertyType.LONG)
        {
            return "mutableBuffer.putLong(initialOffset + " + getOffsetName(property.getName())
                +
                ", value, java.nio.ByteOrder.LITTLE_ENDIAN)";
        }
        else if (property.getType() == EiderPropertyType.SHORT)
        {
            return "mutableBuffer.putShort(initialOffset + " + getOffsetName(property.getName())
                +
                ", value, java.nio.ByteOrder.LITTLE_ENDIAN)";
        }
        else if (property.getType() == EiderPropertyType.FIXED_STRING)
        {
            return "mutableBuffer.putStringWithoutLengthAscii(initialOffset + " + getOffsetName(property.getName())
                +
                ", value)";
        }
        else if (property.getType() == EiderPropertyType.BOOLEAN)
        {
            return "mutableBuffer.putByte(initialOffset + " + getOffsetName(property.getName())
                +
                ", value ? (byte)1 : (byte)0)";
        }
        return "// unsupported type " + property.getType().name();
    }

    private MethodSpec genReadProperty(PreprocessedEiderProperty property)
    {
        return MethodSpec.methodBuilder("read" + upperFirst(property.getName()))
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("Reads " + property.getName() + " as stored in the buffer.")
            .returns(fromType(property.getType()))
            .addStatement(bufferRead(property))
            .build();
    }

    private String bufferRead(PreprocessedEiderProperty property)
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
            int length = Integer.parseInt(property.getAnnotations().get(MAXLENGTH));
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
        else if (property.getType() == EiderPropertyType.SHORT)
        {
            return "return buffer.getShort(initialOffset + " + getOffsetName(property.getName())
                +
                ")";
        }
        return "// unsupported type " + property.getType().name();
    }


    private FieldSpec buildEiderIdField(short eiderId)
    {
        return FieldSpec
            .builder(short.class, "EIDER_ID")
            .addJavadoc("The eider spec id for this type. Useful in switch statements to detect type in first 16bits.")
            .addModifiers(Modifier.STATIC)
            .addModifiers(Modifier.PUBLIC)
            .addModifiers(Modifier.FINAL)
            .initializer(Short.toString(eiderId))
            .build();
    }

    private FieldSpec buildEiderGroupIdField(short groupId)
    {
        return FieldSpec
            .builder(short.class, "EIDER_GROUP_ID")
            .addJavadoc("The eider group id for this type. "
                +
                "Useful in switch statements to detect group in second 16bits.")
            .addModifiers(Modifier.STATIC)
            .addModifiers(Modifier.PUBLIC)
            .addModifiers(Modifier.FINAL)
            .initializer(Short.toString(groupId))
            .build();
    }

    private MethodSpec buildEiderId()
    {
        return MethodSpec.methodBuilder("eiderId")
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("Returns the eider sequence.\n"
                +
                "@return EIDER_ID.\n")
            .returns(short.class)
            .addStatement("return EIDER_ID")
            .build();

    }

    private MethodSpec buildSetUnderlyingBuffer(PreprocessedEiderObject object)
    {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("setUnderlyingBuffer")
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .addJavadoc("Uses the provided {@link org.agrona.DirectBuffer} from the given offset.\n"
                +
                "@param buffer - buffer to read from and write to.\n"
                +
                "@param offset - offset to begin reading from/writing to in the buffer.\n")
            .addParameter(DirectBuffer.class, BUFFER)
            .addParameter(int.class, OFFSET)
            .addStatement("this.initialOffset = offset")
            .addStatement("this.buffer = buffer")
            .beginControlFlow("if (buffer instanceof UnsafeBuffer)")
            .addStatement(UNSAFE_BUFFER + " = (UnsafeBuffer) buffer")
            .addStatement(MUTABLE_BUFFER + " = (MutableDirectBuffer) buffer")
            .addStatement("isUnsafe = true")
            .addStatement("isMutable = true")
            .endControlFlow()
            .beginControlFlow("else if (buffer instanceof MutableDirectBuffer)")
            .addStatement(MUTABLE_BUFFER + " = (MutableDirectBuffer) buffer")
            .addStatement("isUnsafe = false")
            .addStatement("isMutable = true")
            .endControlFlow()
            .beginControlFlow("else")
            .addStatement("isUnsafe = false")
            .addStatement("isMutable = false")
            .endControlFlow();

        if (object.isTransactional())
        {
            builder.addStatement(TRANSACTION_COPY_BUFFER_SET_FALSE);
        }

        if (containsKeyField(object))
        {
            builder.addStatement("keyLocked = false");
        }

        builder.addStatement("buffer.checkLimit(initialOffset + BUFFER_LENGTH)");
        return builder.build();
    }


    private MethodSpec buildSetUnderlyingBufferAndWriteHeader()
    {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("setBufferWriteHeader")
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .addJavadoc("Uses the provided {@link org.agrona.DirectBuffer} from the given offset.\n"
                +
                "@param buffer - buffer to read from and write to.\n"
                +
                "@param offset - offset to begin reading from/writing to in the buffer.\n")
            .addParameter(DirectBuffer.class, BUFFER)
            .addParameter(int.class, OFFSET)
            .addStatement("setUnderlyingBuffer(buffer, offset)")
            .addStatement("writeHeader()");

        return builder.build();
    }

    private List<PreprocessedEiderProperty> indexFields(PreprocessedEiderObject object)
    {
        final List<PreprocessedEiderProperty> result = new ArrayList<>();
        if (objectHasIndexedField(object))
        {
            for (PreprocessedEiderProperty prop : object.getPropertyList())
            {
                if (prop.getAnnotations().get(INDEXED).equalsIgnoreCase(TRUE))
                {
                    result.add(prop);
                }
            }
        }
        return result;
    }

    private boolean isUniqueIndexOnProp(PreprocessedEiderProperty prop)
    {
        if (prop.getAnnotations().get(INDEXED).equalsIgnoreCase(TRUE)
            && prop.getAnnotations().get(UNIQUE).equalsIgnoreCase(TRUE))
        {
            return true;
        }
        return false;
    }
}
