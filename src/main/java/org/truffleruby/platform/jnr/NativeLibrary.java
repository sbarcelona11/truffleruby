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
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.source.Source;

import java.util.Collection;

public class NativeLibrary {

    private final TruffleLanguage.Env env;

    private TruffleObject library;

    public NativeLibrary(TruffleLanguage.Env env, Collection<String> libraryNames, Collection<String> searchPaths) {
        this.env = env;
        library = loadLibrary(libraryNames);
    }

    public TruffleObject lookupSymbol(String name) {
        try {
            return (TruffleObject) ForeignAccess.sendRead(Message.READ.createNode(), library, name);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private final TruffleObject loadLibrary(Collection<String> names) {
        for (String name : names) {
            if (name.equals("c")) {
                final Source source = Source.newBuilder("default").name("default").mimeType("application/x-native").build();
                return (TruffleObject) env.parse(source).call();
            }

            final TruffleObject library = loadLibrary(name);

            if (library != null) {
                return library;
            }
        }

        throw new UnsupportedOperationException();
    }

    private final TruffleObject loadLibrary(String name) {
        final String loadExpression = String.format("load \"%s\"", name);
        final Source source = Source.newBuilder(loadExpression).name("(load " + name + ")").mimeType("application/x-native").build();

        try {
            return (TruffleObject) env.parse(source).call();
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

}
