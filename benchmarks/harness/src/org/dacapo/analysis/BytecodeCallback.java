/**
 * Example usage:
 *
 * java -javaagent:<dacapo_version>/jar/bccagent.jar -Ddacapo.bcc.yml=<output_file> -jar <dacapo_version>.jar -callback org.dacapo.analysis.BytecodeCallback <benchmark>
 */
package org.dacapo.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.io.File;
import java.io.PrintStream;
import org.dacapo.harness.Callback;
import org.dacapo.harness.CommandLineArgs;
import sun.misc.Unsafe;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/*
 * This code depends on the bytecode instrumentation package located
 * in the tools/alloc/bcc directory.  The package is built as part of a
 * standard build and the resulting jar file is included in the jar
 * directory of the resulting dacapo artifact.
 */
public class BytecodeCallback extends Callback {
    private static final String YML_FILENAME_PROPERTY = "dacapo.bcc.yml";

    private static int iteration = 0;
    private static String ymlSuffix = "";

    public static long classesTransformed = 0;
    public static Long bytecodesTransformed = new Long(0);
    public static long bytecodesExecuted = 0;
    public static long callsExecuted = 0;

    private static HashMap<Integer, Long> executed = new HashMap();
    private static HashMap<Integer, Long> called = new HashMap();
    private static List<String> skipped = new ArrayList();
    // typename -> fieldname -> FieldMetadata
    private static HashMap<String, HashMap<String, FieldMetadata>> fieldDereferenced = new HashMap();
    private static final Unsafe UNSAFE;

    static {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = (Unsafe) theUnsafe.get(null);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static class FieldMetadata {
        long offset;
        long derefCount;

        FieldMetadata(long offset) {
            this.offset = offset;
            this.derefCount = 0;
        }

        public long getOffset() {
            return offset;
        }

        public long getDerefCount() {
            return derefCount;
        }

        public void incrementDerefCount() {
            derefCount++;
        }
    }

    private static long[] opcodes = new long[255];

    public static final String[] mnemonic = { "nop", "aconst_null", "iconst_m1", "iconst_0", "iconst_1", "iconst_2",
            "iconst_3", "iconst_4", "iconst_5", "lconst_0", "lconst_1", "fconst_0", "fconst_1", "fconst_2", "dconst_0",
            "dconst_1", "bipush", "sipush", "ldc", "ldc_w", "ldc2_w", "iload", "lload", "fload", "dload", "aload",
            "iload_0", "iload_1", "iload_2", "iload_3", "lload_0", "lload_1", "lload_2", "lload_3", "fload_0",
            "fload_1", "fload_2", "fload_3", "dload_0", "dload_1", "dload_2", "dload_3", "aload_0", "aload_1",
            "aload_2", "aload_3", "iaload", "laload", "faload", "daload", "aaload", "baload", "caload", "saload",
            "istore", "lstore", "fstore", "dstore", "astore", "istore_0", "istore_1", "istore_2", "istore_3",
            "lstore_0", "lstore_1", "lstore_2", "lstore_3", "fstore_0", "fstore_1", "fstore_2", "fstore_3", "dstore_0",
            "dstore_1", "dstore_2", "dstore_3", "astore_0", "astore_1", "astore_2", "astore_3", "iastore", "lastore",
            "fastore", "dastore", "aastore", "bastore", "castore", "sastore", "pop", "pop2", "dup", "dup_x1", "dup_x2",
            "dup2", "dup2_x1", "dup2_x2", "swap", "iadd", "ladd", "fadd", "dadd", "isub", "lsub", "fsub", "dsub",
            "imul", "lmul", "fmul", "dmul", "idiv", "ldiv", "fdiv", "ddiv", "irem", "lrem", "frem", "drem", "ineg",
            "lneg", "fneg", "dneg", "ishl", "lshl", "ishr", "lshr", "iushr", "lushr", "iand", "land", "ior", "lor",
            "ixor", "lxor", "iinc", "i2l", "i2f", "i2d", "l2i", "l2f", "l2d", "f2i", "f2l", "f2d", "d2i", "d2l", "d2f",
            "i2b", "i2c", "i2s", "lcmp", "fcmpl", "fcmpg", "dcmpl", "dcmpg", "ifeq", "ifne", "iflt", "ifge", "ifgt",
            "ifle", "if_icmpeq", "if_icmpne", "if_icmplt", "if_icmpge", "if_icmpgt", "if_icmple", "if_acmpeq",
            "if_acmpne", "goto", "jsr", "ret", "tableswitch", "lookupswitch", "ireturn", "lreturn", "freturn",
            "dreturn", "areturn", "return", "getstatic", "putstatic", "getfield", "putfield", "invokevirtual",
            "invokespecial", "invokestatic", "invokeinterface", "invokedynamic", "new", "newarray", "anewarray",
            "arraylength", "athrow", "checkcast", "instanceof", "monitorenter", "monitorexit", "wide", "multianewarray",
            "ifnull", "ifnonnull", "goto_w", "jsr_w", "breakpoint", "", "", "", "", "", "", "", "", "", "", "", "", "",
            "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
            "", "", "", "", "", "", "", "", "", "", "", "impdep1", "impdep2" };

    public BytecodeCallback(CommandLineArgs args) {
        super(args);
    }

    /* Immediately prior to start of the benchmark */
    @Override
    public void start(String benchmark) {
        iterationStart(isWarmup());
        super.start(benchmark);
    };

    /* Immediately after the end of the benchmark */
    @Override
    public void stop(long duration) {
        super.stop(duration);
        iterationStop(isWarmup());
    };

    @Override
    public void complete(String benchmark, boolean valid) {
        super.complete(benchmark, valid);
        benchmarkComplete(valid);
    };

    /* Benchmark Callback hooks */
    private static void iterationStart(boolean warmup) {
        reset();
    }

    private static void iterationStop(boolean warmup) {
        ymlSuffix = warmup ? "." + iteration : "";
    }

    private static boolean check() {
        int pre = executed.keySet().size();
        executed.values().remove(null);
        int post = executed.keySet().size();
        if (post != pre) {
            System.err.println("Warning deleted " + (pre - post) + " null entries (" + pre + " -> " + post + ")");
        }

        long esum = 0;
        for (int id : executed.keySet()) {
            esum += executed.get(id);
        }
        long osum = 0;
        for (int i = 0; i < opcodes.length; i++) {
            osum += opcodes[i];
        }
        if (esum != bytecodesExecuted || osum != bytecodesExecuted) {
            System.err.println("WARNING: inconsistent count of bytecodes executed (" + esum + ", " + osum + ", "
                    + bytecodesExecuted + ")");
            return false;
        }
        return true;
    }

    private synchronized static void benchmarkComplete(boolean valid) {
        check();

        PrintStream yml = System.out;

        String ymlfile = System.getProperty(YML_FILENAME_PROPERTY);
        if (ymlfile == null) {
            System.out.println("The '" + YML_FILENAME_PROPERTY
                    + "' system property is not set, so printing bytecode yml to console.");
        } else {
            ymlfile += ymlSuffix;
            try {
                yml = new PrintStream(new File(ymlfile));
            } catch (Exception e) {
                System.err.println("Could not open '" + ymlfile + "', so printing bytecode yml to console.");
            }
        }

        Collection<Long> v = executed.values();
        int pre = v.size();
        v.removeIf(Objects::isNull);
        int post = v.size();
        if (pre != post) {
            System.err.println("Warning deleted " + (pre - post) + " null entries (" + pre + " -> " + post + ").");
        }
        List<Long> bytecodefreq = new ArrayList<Long>(v);
        Collections.sort(bytecodefreq);
        int uniq = bytecodefreq.size();
        long p90 = uniq > 0 ? bytecodefreq.get((uniq - 1) - (uniq / 10)) : 0;
        long p99 = uniq > 0 ? bytecodefreq.get((uniq - 1) - (uniq / 100)) : 0;
        long p999 = uniq > 0 ? bytecodefreq.get((uniq - 1) - (uniq / 1000)) : 0;
        long p9999 = uniq > 0 ? bytecodefreq.get((uniq - 1) - (uniq / 10000)) : 0;

        yml.println("# These statistics can be generated from a dacapo release using a command line like:");
        yml.println(
                "#    java -javaagent:<dacapo_version>/jar/bccagent.jar -Ddacapo.bcc.yml=<output_file> -jar <dacapo_version>.jar -callback org.dacapo.analysis.BytecodeCallback <benchmark>");
        yml.println("#");
        yml.println("skipped-classes: " + skipped.size());
        yml.println("transformed-classes: " + classesTransformed);
        yml.println("transformed-bytecodes: " + bytecodesTransformed);
        yml.println("executed-bytecodes: " + bytecodesExecuted);
        yml.println("executed-bytecodes-unique: " + executed.size());
        yml.println("executed-bytecodes-p90: " + p90);
        yml.println("executed-bytecodes-p99: " + p99);
        yml.println("executed-bytecodes-p999: " + p999);
        yml.println("executed-bytecodes-p9999: " + p9999);
        yml.println("executed-calls: " + callsExecuted);
        yml.println("executed-calls-unique: " + called.size());

        // Print the field dereference counts
        yml.println("field-dereference-counts:");
        for (String owner : fieldDereferenced.keySet()) {
            HashMap<String, FieldMetadata> deref = fieldDereferenced.get(owner);

            LinkedHashMap<String, FieldMetadata> sorted = deref.entrySet()
                    .stream()
                    .sorted(Map.Entry.<String, FieldMetadata>comparingByValue(
                            Comparator.comparingLong(FieldMetadata::getDerefCount)).reversed())
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (e1, e2) -> e1,
                            LinkedHashMap::new));

            yml.println("  " + owner + ":");
            for (String name : sorted.keySet()) {
                FieldMetadata fieldMetadata = sorted.get(name);
                yml.println("    " + name + ":");
                yml.println("      offset: " + fieldMetadata.getOffset());
                yml.println("      deref-count: " + fieldMetadata.getDerefCount());
            }
        }

        yml.print("opcodes:");
        boolean start = true;
        for (int i = 0; i < 255; i++) {
            if (opcodes[i] != 0) {
                yml.print("\n  " + mnemonic[i] + ": " + opcodes[i]);
                start = false;
            }
        }

        for (String msg : skipped) {
            yml.println("# " + msg);
        }

        if (yml != System.out) {
            try {
                yml.close();
            } catch (Exception e) {
                System.err.println("Exception closing file: " + e);
            }
        }
    }

    private synchronized static void reset() {
        bytecodesExecuted = 0;
        callsExecuted = 0;
        executed = new HashMap();
        called = new HashMap();
        for (int i = 0; i < 255; i++) {
            opcodes[i] = 0;
        }
    }

    static void preMainClassLoaded(Class clazz) {
    }

    static void classTransformed(String className) {
        classesTransformed++;
    }

    public synchronized static int bytecodeTransformed() {
        bytecodesTransformed++;
        if (bytecodesTransformed >= Integer.MAX_VALUE) {
            System.err.println("WARNING: overflow on bytecode ID");
            return Integer.MAX_VALUE;
        } else
            return bytecodesTransformed.intValue();
    }

    public synchronized static void bytecodeExecuted(int opcode, int id) {
        opcodes[opcode]++;

        /* calls */
        if (opcode >= 182 && opcode <= 186) { // invokevirtual, invokespecial, invokestatic, invokeinterface,
                                              // invokedynamic
            callsExecuted++;
            if (called.containsKey(id)) {
                long old = called.get(id);
                called.put(id, ++old);
            } else {
                called.put(id, 1L);
            }
        }

        if (executed.containsKey(id)) {
            long old = executed.get(id);
            executed.put(id, ++old);
        } else {
            executed.put(id, 1L);
        }
        bytecodesExecuted++;
    }

    public synchronized static void fieldDereferenced(Class<?> cls, String name) {
        String owner = cls.getName();
        if (fieldDereferenced.containsKey(owner)) {
            HashMap<String, FieldMetadata> deref = fieldDereferenced.get(owner);
            if (deref.containsKey(name)) {
                FieldMetadata old = deref.get(name);
                old.incrementDerefCount();
            } else {
                FieldMetadata fieldMetadata = retrieveFieldMetadata(cls, name);
                if (fieldMetadata == null) {
                    return;
                }
                fieldMetadata.incrementDerefCount();
                deref.put(name, fieldMetadata);
            }
        } else {
            FieldMetadata fieldMetadata = retrieveFieldMetadata(cls, name);
            if (fieldMetadata == null) {
                return;
            }
            fieldMetadata.incrementDerefCount();
            HashMap<String, FieldMetadata> deref = new HashMap();
            deref.put(name, fieldMetadata);
            fieldDereferenced.put(owner, deref);
        }
    }

    private static FieldMetadata retrieveFieldMetadata(Class<?> cls, String name) {
        try {
            Field f = findField(cls, name);

            Class<?> t = f.getType();
            boolean isArray = t.isArray();
            if (isArray) {
                return null;
            }
            boolean isPrimitive = t.isPrimitive();
            if (isPrimitive) {
                return null;
            }
            long offset = Modifier.isStatic(f.getModifiers())
                    ? UNSAFE.staticFieldOffset(f)
                    : UNSAFE.objectFieldOffset(f);

            return new FieldMetadata(offset);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to retrieve metadata for " + cls.getName() + "." + name, ex);
        }
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }

        return cls.getField(name);
    }

    public synchronized static void classSkipped(String message) {
        skipped.add(message);
    }
}
