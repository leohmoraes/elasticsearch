package org.elasticsearch.painless;

import org.elasticsearch.common.SuppressForbidden;

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;

/**
 * Painless invokedynamic bootstrap for the call site.
 * <p>
 * Has 7 flavors (passed as static bootstrap parameters): dynamic method call,
 * dynamic field load (getter), and dynamic field store (setter), dynamic array load,
 * dynamic array store, iterator, and method reference.
 * <p>
 * When a new type is encountered at the call site, we lookup from the appropriate
 * whitelist, and cache with a guard. If we encounter too many types, we stop caching.
 * <p>
 * Based on the cascaded inlining cache from the JSR 292 cookbook
 * (https://code.google.com/archive/p/jsr292-cookbook/, BSD license)
 */
// NOTE: this class must be public, because generated painless classes are in a different classloader,
// and it needs to be accessible by that code.
public final class DefBootstrap {

    private DefBootstrap() {} // no instance!

    // NOTE: these must be primitive types, see https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-6.html#jvms-6.5.invokedynamic
    /** static bootstrap parameter indicating a dynamic method call, e.g. foo.bar(...) */
    public static final int METHOD_CALL = 0;
    /** static bootstrap parameter indicating a dynamic load (getter), e.g. baz = foo.bar */
    public static final int LOAD = 1;
    /** static bootstrap parameter indicating a dynamic store (setter), e.g. foo.bar = baz */
    public static final int STORE = 2;
    /** static bootstrap parameter indicating a dynamic array load, e.g. baz = foo[bar] */
    public static final int ARRAY_LOAD = 3;
    /** static bootstrap parameter indicating a dynamic array store, e.g. foo[bar] = baz */
    public static final int ARRAY_STORE = 4;
    /** static bootstrap parameter indicating a dynamic iteration, e.g. for (x : y) */
    public static final int ITERATOR = 5;
    /** static bootstrap parameter indicating a dynamic method reference, e.g. foo::bar */
    public static final int REFERENCE = 6;
    /** static bootstrap parameter indicating a unary math operator, e.g. ~foo */
    public static final int UNARY_OPERATOR = 7;
    /** static bootstrap parameter indicating a binary math operator, e.g. foo / bar */
    public static final int BINARY_OPERATOR = 8;
    /** static bootstrap parameter indicating a shift operator, e.g. foo &gt;&gt; bar */
    public static final int SHIFT_OPERATOR = 9;
    
    // constants for the flags parameter of operators
    /** 
     * static bootstrap parameter indicating the binary operator allows nulls (e.g. == and +) 
     * <p>
     * requires additional {@link MethodHandles#catchException} guard, which will invoke
     * the fallback if a null is encountered.
     */
    public static final int OPERATOR_ALLOWS_NULL = 1 << 0;
    
    /**
     * static bootstrap parameter indicating the binary operator is part of compound assignment (e.g. +=).
     * 
     */
    public static final int OPERATOR_COMPOUND_ASSIGNMENT = 1 << 1;

    /**
     * CallSite that implements the polymorphic inlining cache (PIC).
     */
    static final class PIC extends MutableCallSite {
        /** maximum number of types before we go megamorphic */
        static final int MAX_DEPTH = 5;

        private final Lookup lookup;
        private final String name;
        private final int flavor;
        private final Object[] args;
        int depth; // pkg-protected for testing

        PIC(Lookup lookup, String name, MethodType type, int flavor, Object[] args) {
            super(type);
            if (type.parameterType(0) != Object.class) {
                throw new BootstrapMethodError("The receiver type (1st arg) of invokedynamic descriptor must be Object.");
            }
            this.lookup = lookup;
            this.name = name;
            this.flavor = flavor;
            this.args = args;

            MethodHandle fallback = FALLBACK.bindTo(this)
              .asCollector(Object[].class, type.parameterCount())
              .asType(type);

            setTarget(fallback);
        }
        
        /**
         * guard method for inline caching: checks the receiver's class is the same
         * as the cached class
         */
        static boolean checkClass(Class<?> clazz, Object receiver) {
            return receiver.getClass() == clazz;
        }

        /**
         * Does a slow lookup against the whitelist.
         */
        private MethodHandle lookup(int flavor, String name, Class<?> receiver, Object[] callArgs) throws Throwable {
            switch(flavor) {
                case METHOD_CALL:
                    return Def.lookupMethod(lookup, type(), receiver, name, callArgs, (Long) this.args[0]);
                case LOAD:
                    return Def.lookupGetter(receiver, name);
                case STORE:
                    return Def.lookupSetter(receiver, name);
                case ARRAY_LOAD:
                    return Def.lookupArrayLoad(receiver);
                case ARRAY_STORE:
                    return Def.lookupArrayStore(receiver);
                case ITERATOR:
                    return Def.lookupIterator(receiver);
                case REFERENCE:
                    return Def.lookupReference(lookup, (String) this.args[0], receiver, name);
                default: throw new AssertionError();
            }
        }
        
        /**
         * Creates the {@link MethodHandle} for the megamorphic call site
         * using {@link ClassValue} and {@link MethodHandles#exactInvoker(MethodType)}:
         * <p>
         * TODO: Remove the variable args and just use {@code type()}!
         */
        private MethodHandle createMegamorphicHandle(final Object[] callArgs) throws Throwable {
            final MethodType type = type();
            final ClassValue<MethodHandle> megamorphicCache = new ClassValue<MethodHandle>() {
                @Override
                protected MethodHandle computeValue(Class<?> receiverType) {
                    // it's too stupid that we cannot throw checked exceptions... (use rethrow puzzler):
                    try {
                        return lookup(flavor, name, receiverType, callArgs).asType(type);
                    } catch (Throwable t) {
                        Def.rethrow(t);
                        throw new AssertionError();
                    }
                }
            };
            MethodHandle cacheLookup = MEGAMORPHIC_LOOKUP.bindTo(megamorphicCache);
            cacheLookup = MethodHandles.dropArguments(cacheLookup,
                    1, type.parameterList().subList(1, type.parameterCount()));
            return MethodHandles.foldArguments(MethodHandles.exactInvoker(type), cacheLookup);            
        }

        /**
         * Called when a new type is encountered (or, when we have encountered more than {@code MAX_DEPTH}
         * types at this call site and given up on caching using this fallback and we switch to a
         * megamorphic cache using {@link ClassValue}).
         */
        @SuppressForbidden(reason = "slow path")
        Object fallback(final Object[] callArgs) throws Throwable {
            if (depth >= MAX_DEPTH) {
                // we revert the whole cache and build a new megamorphic one
                final MethodHandle target = this.createMegamorphicHandle(callArgs);
                
                setTarget(target);
                return target.invokeWithArguments(callArgs);                    
            } else {
                final Class<?> receiver = callArgs[0].getClass();
                final MethodHandle target = lookup(flavor, name, receiver, callArgs).asType(type());
    
                MethodHandle test = CHECK_CLASS.bindTo(receiver);
                MethodHandle guard = MethodHandles.guardWithTest(test, target, getTarget());
                
                depth++;
    
                setTarget(guard);
                return target.invokeWithArguments(callArgs);
            }
        }

        private static final MethodHandle CHECK_CLASS;
        private static final MethodHandle FALLBACK;
        private static final MethodHandle MEGAMORPHIC_LOOKUP;
        static {
            final Lookup lookup = MethodHandles.lookup();
            final Lookup publicLookup = MethodHandles.publicLookup();
            try {
                CHECK_CLASS = lookup.findStatic(lookup.lookupClass(), "checkClass",
                                              MethodType.methodType(boolean.class, Class.class, Object.class));
                FALLBACK = lookup.findVirtual(lookup.lookupClass(), "fallback",
                        MethodType.methodType(Object.class, Object[].class));
                MethodHandle mh = publicLookup.findVirtual(ClassValue.class, "get",
                        MethodType.methodType(Object.class, Class.class));
                mh = MethodHandles.filterArguments(mh, 1, 
                        publicLookup.findVirtual(Object.class, "getClass", MethodType.methodType(Class.class)));
                MEGAMORPHIC_LOOKUP = mh.asType(mh.type().changeReturnType(MethodHandle.class));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }
    
    /**
     * CallSite that implements the monomorphic inlining cache (for operators).
     */
    static final class MIC extends MutableCallSite {
        private boolean initialized;
        
        private final String name;
        private final int flavor;
        private final int flags;

        MIC(String name, MethodType type, int flavor, int flags) {
            super(type);
            this.name = name;
            this.flavor = flavor;
            this.flags = flags;
            
            MethodHandle fallback = FALLBACK.bindTo(this)
              .asCollector(Object[].class, type.parameterCount())
              .asType(type);

            setTarget(fallback);
        }
        
        /**
         * Does a slow lookup for the operator
         */
        private MethodHandle lookup(Object[] args) throws Throwable {
            switch(flavor) {
                case UNARY_OPERATOR:
                case SHIFT_OPERATOR:
                    // shifts are treated as unary, as java allows long arguments without a cast (but bits are ignored)
                    MethodHandle unary = DefMath.lookupUnary(args[0].getClass(), name);
                    if ((flags & OPERATOR_COMPOUND_ASSIGNMENT) != 0) {
                        unary = DefMath.cast(args[0].getClass(), unary);
                    }
                    return unary;
                case BINARY_OPERATOR:
                    if (args[0] == null || args[1] == null) {
                        return lookupGeneric(); // can handle nulls, casts if supported
                    } else {
                        MethodHandle binary = DefMath.lookupBinary(args[0].getClass(), args[1].getClass(), name);
                        if ((flags & OPERATOR_COMPOUND_ASSIGNMENT) != 0) {
                            binary = DefMath.cast(args[0].getClass(), binary);
                        }
                        return binary;
                    }
                default: throw new AssertionError();
            }
        }
        
        private MethodHandle lookupGeneric() throws Throwable {
            if ((flags & OPERATOR_COMPOUND_ASSIGNMENT) != 0) {
                return DefMath.lookupGenericWithCast(name);
            } else {
                return DefMath.lookupGeneric(name);
            }
        }
        
        /**
         * Called when a new type is encountered (or, when we have encountered more than {@code MAX_DEPTH}
         * types at this call site and given up on caching).
         */
        @SuppressForbidden(reason = "slow path")
        Object fallback(Object[] args) throws Throwable {
            if (initialized) {
                // caching defeated
                MethodHandle generic = lookupGeneric();
                setTarget(generic.asType(type()));
                return generic.invokeWithArguments(args);
            }
            
            final MethodType type = type();
            final MethodHandle target = lookup(args).asType(type);

            final MethodHandle test;
            if (flavor == BINARY_OPERATOR || flavor == SHIFT_OPERATOR) {
                // some binary operators support nulls, we handle them separate
                Class<?> clazz0 = args[0] == null ? null : args[0].getClass();
                Class<?> clazz1 = args[1] == null ? null : args[1].getClass();
                if (type.parameterType(1) != Object.class) {
                    // case 1: only the receiver is unknown, just check that
                    MethodHandle unaryTest = CHECK_LHS.bindTo(clazz0);
                    test = unaryTest.asType(unaryTest.type()
                                            .changeParameterType(0, type.parameterType(0)));
                } else if (type.parameterType(0) != Object.class) {
                    // case 2: only the argument is unknown, just check that
                    MethodHandle unaryTest = CHECK_RHS.bindTo(clazz0).bindTo(clazz1);
                    test = unaryTest.asType(unaryTest.type()
                                            .changeParameterType(0, type.parameterType(0))
                                            .changeParameterType(1, type.parameterType(1)));
                } else {
                    // case 3: check both receiver and argument
                    MethodHandle binaryTest = CHECK_BOTH.bindTo(clazz0).bindTo(clazz1);
                    test = binaryTest.asType(binaryTest.type()
                                            .changeParameterType(0, type.parameterType(0))
                                            .changeParameterType(1, type.parameterType(1)));
                }
            } else {
                // unary operator
                MethodHandle receiverTest = CHECK_LHS.bindTo(args[0].getClass());
                test = receiverTest.asType(receiverTest.type()
                                        .changeParameterType(0, type.parameterType(0)));
            }

            MethodHandle guard = MethodHandles.guardWithTest(test, target, getTarget());
            // very special cases, where even the receiver can be null (see JLS rules for string concat)
            // we wrap + with an NPE catcher, and use our generic method in that case.
            if (flavor == BINARY_OPERATOR && (flags & OPERATOR_ALLOWS_NULL) != 0) {
                MethodHandle handler = MethodHandles.dropArguments(lookupGeneric().asType(type()), 
                                                                   0, 
                                                                   NullPointerException.class);
                guard = MethodHandles.catchException(guard, NullPointerException.class, handler);
            }
            
            initialized = true;

            setTarget(guard);
            return target.invokeWithArguments(args);
        }
        
        /**
         * guard method for inline caching: checks the receiver's class is the same
         * as the cached class
         */
        static boolean checkLHS(Class<?> clazz, Object leftObject) {
            return leftObject.getClass() == clazz;
        }

        /**
         * guard method for inline caching: checks the first argument is the same
         * as the cached first argument.
         */
        static boolean checkRHS(Class<?> left, Class<?> right, Object leftObject, Object rightObject) {
            return rightObject.getClass() == right;
        }
        
        /**
         * guard method for inline caching: checks the receiver's class and the first argument
         * are the same as the cached receiver and first argument.
         */
        static boolean checkBoth(Class<?> left, Class<?> right, Object leftObject, Object rightObject) {
            return leftObject.getClass() == left && rightObject.getClass() == right;
        }
        
        private static final MethodHandle CHECK_LHS;
        private static final MethodHandle CHECK_RHS;
        private static final MethodHandle CHECK_BOTH;
        private static final MethodHandle FALLBACK;
        static {
            final Lookup lookup = MethodHandles.lookup();
            try {
                CHECK_LHS = lookup.findStatic(lookup.lookupClass(), "checkLHS",
                                              MethodType.methodType(boolean.class, Class.class, Object.class));
                CHECK_RHS = lookup.findStatic(lookup.lookupClass(), "checkRHS",
                                              MethodType.methodType(boolean.class, Class.class, Class.class, Object.class, Object.class));
                CHECK_BOTH = lookup.findStatic(lookup.lookupClass(), "checkBoth",
                                              MethodType.methodType(boolean.class, Class.class, Class.class, Object.class, Object.class));
                FALLBACK = lookup.findVirtual(lookup.lookupClass(), "fallback",
                                              MethodType.methodType(Object.class, Object[].class));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

    /**
     * invokeDynamic bootstrap method
     * <p>
     * In addition to ordinary parameters, we also take a static parameter {@code flavor} which
     * tells us what type of dynamic call it is (and which part of whitelist to look at).
     * <p>
     * see https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-6.html#jvms-6.5.invokedynamic
     */
    public static CallSite bootstrap(Lookup lookup, String name, MethodType type, int flavor, Object... args) {
        // validate arguments
        switch(flavor) {
            // "function-call" like things get a polymorphic cache
            case METHOD_CALL:
                if (args.length != 1) {
                    throw new BootstrapMethodError("Invalid number of parameters for method call");
                }
                if (args[0] instanceof Long == false) {
                    throw new BootstrapMethodError("Illegal parameter for method call: " + args[0]);
                }
                long recipe = (Long) args[0];
                if (Long.bitCount(recipe) > type.parameterCount()) {
                    throw new BootstrapMethodError("Illegal recipe for method call: too many bits");
                }
                return new PIC(lookup, name, type, flavor, args);
            case LOAD:
            case STORE:
            case ARRAY_LOAD:
            case ARRAY_STORE:
            case ITERATOR:
                if (args.length > 0) {
                    throw new BootstrapMethodError("Illegal static bootstrap parameters for flavor: " + flavor);
                }
                return new PIC(lookup, name, type, flavor, args);
            case REFERENCE:
                if (args.length != 1) {
                    throw new BootstrapMethodError("Invalid number of parameters for reference call");
                }
                if (args[0] instanceof String == false) {
                    throw new BootstrapMethodError("Illegal parameter for reference call: " + args[0]);
                }
                return new PIC(lookup, name, type, flavor, args);

            // operators get monomorphic cache, with a generic impl for a fallback
            case UNARY_OPERATOR:
            case SHIFT_OPERATOR:
            case BINARY_OPERATOR:
                if (args.length != 1) {
                    throw new BootstrapMethodError("Invalid number of parameters for operator call");
                }
                if (args[0] instanceof Integer == false) {
                    throw new BootstrapMethodError("Illegal parameter for reference call: " + args[0]);
                }
                int flags = (int)args[0];
                if ((flags & OPERATOR_ALLOWS_NULL) != 0 && flavor != BINARY_OPERATOR) {
                    // we just don't need it anywhere else.
                    throw new BootstrapMethodError("This parameter is only supported for BINARY_OPERATORs");
                }
                if ((flags & OPERATOR_COMPOUND_ASSIGNMENT) != 0 && flavor != BINARY_OPERATOR) {
                    // we just don't need it anywhere else.
                    throw new BootstrapMethodError("This parameter is only supported for BINARY_OPERATORs");
                }
                return new MIC(name, type, flavor, flags);
            default:
                throw new BootstrapMethodError("Illegal static bootstrap parameter for flavor: " + flavor);
        }
    }
}
