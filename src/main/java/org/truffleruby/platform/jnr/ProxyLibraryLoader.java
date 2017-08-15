/*
 * Copyright (c) 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.platform.jnr;

import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.Node;
import jnr.ffi.LibraryOption;
import jnr.ffi.annotations.IgnoreError;
import jnr.ffi.annotations.In;
import jnr.ffi.provider.Invoker;
import jnr.ffi.provider.LoadedLibrary;
import jnr.ffi.provider.NativeInvocationHandler;
import org.truffleruby.RubyContext;
import org.truffleruby.extra.ffi.Pointer;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ProxyLibraryLoader {

    private final NativeLibrary nativeLibrary;

    public ProxyLibraryLoader(NativeLibrary nativeLibrary) {
        this.nativeLibrary = nativeLibrary;
    }

    public <T> T loadLibrary(Class<T> interfaceClass, Map<LibraryOption, Object> options) {
        final NativeInvocationHandler invocationHandler = new NativeInvocationHandler(new LazyInvokerMap());
        final Class<?>[] interfaces = new Class<?>[]{interfaceClass, LoadedLibrary.class};
        return interfaceClass.cast(Proxy.newProxyInstance(interfaceClass.getClassLoader(), interfaces, invocationHandler));
    }

    private class LazyInvokerMap extends AbstractMap<Method, Invoker> {

        private final Map<Method, Invoker> invokers = new HashMap<>();

        @Override
        public Set<Entry<Method, Invoker>> entrySet() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Invoker get(Object key) {
            if (!(key instanceof Method)) {
                throw new UnsupportedOperationException();
            }

            final Method method = (Method) key;

            Invoker invoker = invokers.get(method);

            if (invoker == null) {
                invoker = createInvoker(method);
                invokers.put(method, invoker);
            }

            return invoker;
        }

        private Invoker createInvoker(Method method) {
            if (method.getName().equals("getRuntime")) {
                return (self, parameters) -> NFIRuntime.INSTANCE;
            }

            for (Annotation annotation : method.getAnnotations()) {
                if (annotation instanceof IgnoreError) {
                    continue;
                }

                throw new UnsupportedOperationException(annotation.toString());
            }

            final TruffleObject symbol = nativeLibrary.lookupSymbol(method.getName());
            final String signature = createSignature(method);
            final TruffleObject bound = bind(symbol, signature);

            final Node execute = Message.createExecute(method.getParameterCount()).createNode();

            return (self, parameters) -> {
                final int parametersCount;

                if (parameters == null) {
                    parametersCount = 0;
                } else {
                    parametersCount = parameters.length;
                }

                final Object[] translatedParameters = new Object[parametersCount];

                for (int n = 0; n < parametersCount; n++) {
                    for (Annotation annotation : method.getParameterAnnotations()[n]) {
                        if (annotation instanceof In) {
                            continue;
                        }

                        throw new UnsupportedOperationException(annotation.toString());
                    }

                    translatedParameters[n] = translateToNFI(parameters[n]);
                }

                final Object[] unboxedParameters = new Object[parametersCount];

                for (int n = 0; n < parametersCount; n++) {
                    unboxedParameters[n] = unboxToNFI(translatedParameters[n]);
                }

                final Object result;

                try {
                    result = ForeignAccess.sendExecute(execute, bound, unboxedParameters);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                return translateToJNR(result);
            };
        }

        private Object translateToNFI(Object parameter) {
            final Class<?> type = parameter.getClass();

            if (type == Integer.class) {
                return parameter;
            } else if (type == byte[].class) {
                final byte[] array = (byte[]) parameter;
                final Pointer pointer = Pointer.malloc(array.length);
                pointer.enableAutorelease(RubyContext.FIRST_INSTANCE.getFinalizationService());
                pointer.writeBytes(0, array, 0, array.length);
                return pointer;
            } else if (type == String.class) {
                final byte[] bytes = ((String) parameter).getBytes(Charset.defaultCharset());
                final Pointer pointer = Pointer.malloc(bytes.length + 1);
                pointer.enableAutorelease(RubyContext.FIRST_INSTANCE.getFinalizationService());
                pointer.writeBytes(0, bytes, 0, bytes.length);
                pointer.putByte(bytes.length, (byte) 0);
                return pointer;
            } else if (ByteBuffer.class.isAssignableFrom(type)) {
                final ByteBuffer buffer = (ByteBuffer) parameter;
                final Pointer pointer = Pointer.malloc(buffer.capacity());
                pointer.enableAutorelease(RubyContext.FIRST_INSTANCE.getFinalizationService());
                pointer.writeBytes(0, buffer.array(), buffer.arrayOffset(), buffer.capacity());
                return pointer;
            }

            throw new UnsupportedOperationException(type.getName());
        }

        private Object unboxToNFI(Object parameter) {
            if (parameter instanceof Pointer) {
                return ((Pointer) parameter).getAddress();
            } else {
                return parameter;
            }
        }

        private Object translateToJNR(Object result) {
            final Class<?> type = result.getClass();

            if (type == Integer.class) {
                return result;
            } else if (type == Long.class) {
                return result;
            }

            throw new UnsupportedOperationException(type.getName());
        }

        private String createSignature(Method method) {
            final StringBuilder builder = new StringBuilder();

            builder.append("(");

            final Class<?>[] parameterTypes = method.getParameterTypes();

            for (int n = 0; n < parameterTypes.length; n++) {
                builder.append(translateType(parameterTypes[n]));
                if (n + 1 < parameterTypes.length) {
                    builder.append(",");
                }
            }

            builder.append("):");
            builder.append(translateType(method.getReturnType()));

            return builder.toString();
        }

        private String translateType(Class<?> type) {
            if (type == Pointer.class) {
                return "POINTER";
            } else if (type == int.class) {
                return "SINT32";
            } else if (type == long.class) {
                return "SINT64";
            } else if (type == byte[].class) {
                return "POINTER";
            } else if (type == CharSequence.class) {
                return "POINTER";
            } else if (type.isInterface()) {
                return "POINTER";
            } else if (type == ByteBuffer.class) {
                return "POINTER";
            }

            throw new UnsupportedOperationException(type.getName());
        }

        private TruffleObject bind(TruffleObject symbol, String signature) {
            try {
                return (TruffleObject) ForeignAccess.sendInvoke(Message.createInvoke(1).createNode(), symbol, "bind", signature);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

    }

}
