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

import com.oracle.truffle.api.TruffleLanguage;
import jnr.ffi.LibraryLoader;
import jnr.ffi.LibraryOption;

import java.util.Collection;
import java.util.Map;

public class NFILibraryLoader<T> extends LibraryLoader<T> {

    private final TruffleLanguage.Env env;

    protected NFILibraryLoader(TruffleLanguage.Env env, Class<T> interfaceClass) {
        super(interfaceClass);
        this.env = env;
    }

    @Override
    protected T loadLibrary(Class<T> interfaceClass, Collection<String> libraryNames, Collection<String> searchPaths, Map<LibraryOption, Object> options) {
        final NativeLibrary nativeLibrary = new NativeLibrary(env, libraryNames, searchPaths);
        return new ProxyLibraryLoader(nativeLibrary).loadLibrary(interfaceClass, options);
    }

}
