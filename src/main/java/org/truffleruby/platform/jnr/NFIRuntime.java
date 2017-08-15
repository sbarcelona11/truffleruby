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

import jnr.ffi.NativeType;
import jnr.ffi.ObjectReferenceManager;
import jnr.ffi.Runtime;
import jnr.ffi.Type;
import jnr.ffi.TypeAlias;
import jnr.ffi.provider.AbstractRuntime;
import jnr.ffi.provider.ClosureManager;
import jnr.ffi.provider.MemoryManager;
import jnr.ffi.provider.jffi.platform.x86_64.darwin.TypeAliases;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.EnumMap;

public class NFIRuntime extends AbstractRuntime {

    public static final NFIRuntime INSTANCE = new NFIRuntime();

    private final NFIMemoryManager memoryManager = new NFIMemoryManager();

    public NFIRuntime() {
        super(ByteOrder.nativeOrder(), buildTypeMap());
    }

    private static EnumMap<NativeType, Type> buildTypeMap() {
        EnumMap<NativeType, Type> typeMap = new EnumMap<>(NativeType.class);

        for (NativeType type : Arrays.asList(NativeType.USHORT, NativeType.SSHORT)) {
            typeMap.put(type, new NFIType(type, Short.BYTES));
        }

        for (NativeType type : Arrays.asList(NativeType.UINT, NativeType.SINT)) {
            typeMap.put(type, new NFIType(type, Integer.BYTES));
        }

        for (NativeType type : Arrays.asList(NativeType.ADDRESS, NativeType.ULONG, NativeType.SLONG, NativeType.ULONGLONG, NativeType.SLONGLONG)) {
            typeMap.put(type, new NFIType(type, Long.BYTES));
        }

        return typeMap;
    }

    @Override
    public Type findType(TypeAlias type) {
        return findType(TypeAliases.ALIASES.get(type));
    }

    @Override
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    @Override
    public ClosureManager getClosureManager() {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> ObjectReferenceManager<T> newObjectReferenceManager() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getLastError() {
        return 0;
    }

    @Override
    public void setLastError(int error) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isCompatible(Runtime other) {
        throw new UnsupportedOperationException();
    }

}
