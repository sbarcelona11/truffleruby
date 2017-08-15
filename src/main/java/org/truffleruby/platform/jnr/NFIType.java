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
import jnr.ffi.Type;

public class NFIType extends Type {

    private final NativeType nativeType;
    private final int size;

    public NFIType(NativeType nativeType, int size) {
        this.nativeType = nativeType;
        this.size = size;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public int alignment() {
        return size;
    }

    @Override
    public NativeType getNativeType() {
        return nativeType;
    }

}
