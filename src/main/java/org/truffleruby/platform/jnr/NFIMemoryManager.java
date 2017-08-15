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

import jnr.ffi.provider.MemoryManager;
import org.truffleruby.RubyContext;
import org.truffleruby.extra.ffi.Pointer;

import java.nio.ByteBuffer;

public class NFIMemoryManager implements MemoryManager {

    @Override
    public Pointer allocate(int size) {
        return allocateDirect(size);
    }

    @Override
    public Pointer allocateDirect(int size) {
        return allocateDirect(size, true); // TODO try clear=false
    }

    @Override
    public Pointer allocateDirect(int size, boolean clear) {
        final Pointer pointer;

        if (clear) {
            pointer = Pointer.calloc(size);
        } else {
            pointer = Pointer.malloc(size);
        }

        pointer.enableAutorelease(RubyContext.FIRST_INSTANCE.getFinalizationService());

        return pointer;
    }

    @Override
    public Pointer allocateTemporary(int size, boolean clear) {
        return allocateDirect(size, clear);
    }

    @Override
    public Pointer newPointer(ByteBuffer buffer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Pointer newPointer(long address) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Pointer newPointer(long address, long size) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Pointer newOpaquePointer(long address) {
        return new Pointer(address);
    }

}
