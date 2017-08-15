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
import jnr.ffi.Runtime;
import jnr.ffi.provider.FFIProvider;
import org.truffleruby.RubyContext;

public class NFIProvider extends FFIProvider {

    private static final NFIRuntime runtime = NFIRuntime.INSTANCE;

    private final TruffleLanguage.Env env;

    // Called by reflection - set jnr.ffi.provider=org.truffleruby.platform.jnr.NFIProvider

    public NFIProvider() {
        env = RubyContext.FIRST_INSTANCE.getEnv();
        assert env != null;
    }

    @Override
    public Runtime getRuntime() {
        return runtime;
    }

    @Override
    public <T> LibraryLoader<T> createLibraryLoader(Class<T> interfaceClass) {
        return new NFILibraryLoader<T>(env, interfaceClass);
    }

}
