package dev.skidfuscator.obfuscator.skidasm;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.util.TypeUtil;
import lombok.Data;
import org.mapleir.asm.ClassNode;
import org.mapleir.asm.MethodNode;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Wrapper for the ASM annotations to allow support for annotation obfuscation. This is stored in
 * @see dev.skidfuscator.obfuscator.hierarchy.Hierarchy
 *
 * Cool stuff really. It allows to dynamically set the values directly without interacting with
 * ASM. This also stores important stuff such as object value, type, header name etc... in a
 * clean and elegant fashion.
 */
public class SkidAnnotation {
    private final AnnotationNode node;
    private final AnnotationType type;
    private final Skidfuscator skidfuscator;
    private final ClassNode parent;
    private final Map<String, AnnotationValue<?>> values = new HashMap<>();

    public SkidAnnotation(AnnotationNode node, AnnotationType type, Skidfuscator skidfuscator, ClassNode parent) {
        this.node = node;
        this.type = type;
        this.skidfuscator = skidfuscator;
        this.parent = parent;

        this.parse();
    }

    /**
     * @param name Name of the value sought out to be modified (eg @Value(value = "123) )
     *                                                                    ^^^^^ this bit
     * @param <T> Type of the value sought out to be modified
     * @return Annotation Value subclass with the getter and the setter
     */
    public <T> AnnotationValue<T> getValue(String name) {
        return (AnnotationValue) values.get(name);
    }

    /**
     * @return Annotation ASM node to be used
     */
    public AnnotationNode getNode() {
        return node;
    }

    /**
     * @return Annotation ASM type for debugging purposes (need to inform myself on the difference)
     */
    public AnnotationType getType() {
        return type;
    }

    /**
     * @return Value map with all the values and their headings
     */
    public Map<String, AnnotationValue<?>> getValues() {
        return values;
    }

    /**
     * @return Parent class which defines the annotation
     */
    public ClassNode getParent() {
        return parent;
    }

    /**
     * @deprecated PLEASE DO NOT USE THIS IT IS PISS POOR PRACTICE
     * @return Skidfuscator instance (bad bad bad practice to be using this)
     */
    @Deprecated
    public Skidfuscator getSkidfuscator() {
        return skidfuscator;
    }

    /**
     * Function which serves the purpose of parsing an annotation into values that
     * can directly virtually edit the annotation
     */
    private void parse() {
        String name = null;
        for (int i = 0; i < this.node.values.size(); i++) {
            if (i % 2 == 0) {
                // This is the name
                name = (String) node.values.get(i);
            } else {
                final int finalI = i;
                String finalName = name;
                values.put(name, new AnnotationValue<>(name,
                        new Consumer<Object>() {
                            @Override
                            public void accept(Object o) {
                                node.values.set(finalI, o);
                            }
                        },
                        new Supplier<Object>(){
                            @Override
                            public Object get() {
                                return node.values.get(finalI);
                            }
                        },
                        parent.getMethods().stream()
                                .filter(e -> e.getName().equals(finalName))
                                .findFirst()
                                .orElseThrow(IllegalStateException::new)
                ));
            }
        }
    }

    public static class AnnotationValue<T> {
        private final String name;
        private final Type type;
        private final Consumer<T> setter;
        private final Supplier<T> getter;
        private final MethodNode methodNode;

        public AnnotationValue(String name, Consumer<T> setter, Supplier<T> getter, MethodNode methodNode) {
            this.name = name;
            this.setter = setter;
            this.getter = getter;
            this.methodNode = methodNode;
            this.type = get() == null ? TypeUtil.STRING_TYPE : Type.getType(get().getClass());
        }

        public String getName() {
            return name;
        }

        public Type getType() {
            return type;
        }

        public MethodNode getMethodNode() {
            return methodNode;
        }

        public void set(final T t) {
            setter.accept(t);
        }

        public T get() {
            return getter.get();
        }
    }

    public enum AnnotationType {
        VISIBLE(false),
        INVISIBLE(false),
        TYPE_VISIBLE(true),
        TYPE_INVISIBLE(true);

        private final boolean type;

        AnnotationType(boolean type) {
            this.type = type;
        }

        public boolean isType() {
            return type;
        }
    }
}
