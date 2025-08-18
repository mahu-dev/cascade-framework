package io.github.cascade.cache.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Kryo序列化器实现
 * 提供高性能的二进制序列化，比JSON更快更紧凑
 */
public class KryoSerializer<T> implements CacheSerializer<T> {

    private static final Logger log = LoggerFactory.getLogger(KryoSerializer.class);

    private final ThreadLocal<Kryo> kryoThreadLocal;
    private volatile boolean compressionEnabled = false;

    public KryoSerializer() {
        this.kryoThreadLocal = ThreadLocal.withInitial(this::createKryo);
    }

    private Kryo createKryo() {
        Kryo kryo = new Kryo();

        // 配置Kryo
        kryo.setRegistrationRequired(false);
        kryo.setReferences(true);
        kryo.setClassLoader(Thread.currentThread().getContextClassLoader());

        // 注册常用类型以获得更好的性能
        kryo.register(java.util.ArrayList.class);
        kryo.register(java.util.HashMap.class);
        kryo.register(java.util.HashSet.class);
        kryo.register(java.util.LinkedHashMap.class);
        kryo.register(java.util.LinkedHashSet.class);
        kryo.register(java.util.TreeMap.class);
        kryo.register(java.util.TreeSet.class);
        kryo.register(java.util.concurrent.ConcurrentHashMap.class);

        return kryo;
    }

    @Override
    public byte[] serialize(T object) throws SerializationException {
        if (object == null) {
            return null;
        }

        Kryo kryo = kryoThreadLocal.get();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             Output output = new Output(compressionEnabled ?
                     new DeflaterOutputStream(baos) : baos)) {

            kryo.writeClassAndObject(output, object);
            output.flush();

            if (compressionEnabled) {
                ((DeflaterOutputStream) output.getOutputStream()).finish();
            }

            return baos.toByteArray();

        } catch (Exception e) {
            throw new SerializationException("Failed to serialize object with Kryo", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public T deserialize(byte[] bytes) throws SerializationException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        Kryo kryo = kryoThreadLocal.get();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             Input input = new Input(compressionEnabled ?
                     new InflaterInputStream(bais) : bais)) {

            return (T) kryo.readClassAndObject(input);

        } catch (Exception e) {
            throw new SerializationException("Failed to deserialize object with Kryo", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> R deserialize(byte[] bytes, Class<R> clazz) throws SerializationException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        Kryo kryo = kryoThreadLocal.get();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             Input input = new Input(compressionEnabled ?
                     new InflaterInputStream(bais) : bais)) {

            Object object = kryo.readClassAndObject(input);
            if (object != null && !clazz.isInstance(object)) {
                throw new SerializationException(
                        String.format("Deserialized object is not of expected type. Expected: %s, Actual: %s",
                                clazz.getName(), object.getClass().getName()));
            }

            return (R) object;

        } catch (Exception e) {
            throw new SerializationException("Failed to deserialize object with Kryo", e);
        }
    }

    @Override
    public String getName() {
        return "KryoSerializer";
    }

    @Override
    public String getVersion() {
        return "5.x";
    }

    @Override
    public boolean supports(Class<?> clazz) {
        // Kryo可以序列化几乎所有的Java对象
        return true;
    }

    @Override
    public boolean supportsCompression() {
        return true;
    }

    @Override
    public void setCompressionEnabled(boolean enabled) {
        this.compressionEnabled = enabled;
    }

    @Override
    public long getEstimatedOpsPerSecond() {
        return 200000; // Kryo比JSON快很多
    }

    /**
     * 获取当前线程的Kryo实例
     */
    public Kryo getCurrentKryo() {
        return kryoThreadLocal.get();
    }

    /**
     * 检查是否启用了压缩
     */
    public boolean isCompressionEnabled() {
        return compressionEnabled;
    }
}