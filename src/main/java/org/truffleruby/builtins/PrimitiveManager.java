/*
 * Copyright (c) 2015, 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.builtins;

import com.oracle.truffle.api.TruffleOptions;
import com.oracle.truffle.api.dsl.NodeFactory;

import org.truffleruby.collections.ConcurrentOperations;
import org.truffleruby.core.rubinius.UndefinedPrimitiveNodesFactory.UndefinedPrimitiveNodeFactory;
import org.truffleruby.language.RubyNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the available Rubinius primitive calls.
 */
public class PrimitiveManager {

    private final Map<String, String> lazyPrimitiveClasses = new ConcurrentHashMap<>();

    private final Map<String, PrimitiveNodeConstructor> primitives = new ConcurrentHashMap<>();

    private final PrimitiveNodeConstructor undefinedPrimitive;

    public PrimitiveManager() {
        final NodeFactory<? extends RubyNode> nodeFactory = UndefinedPrimitiveNodeFactory.getInstance();
        final Primitive annotation = nodeFactory.getNodeClass().getAnnotation(Primitive.class);
        undefinedPrimitive = new PrimitiveNodeConstructor(annotation, nodeFactory);
    }

    public PrimitiveNodeConstructor getPrimitive(String name) {
        final PrimitiveNodeConstructor constructor = primitives.get(name);
        if (constructor != null) {
            return constructor;
        }

        if (!TruffleOptions.AOT) {
            final String lazyPrimitive = lazyPrimitiveClasses.get(name);
            if (lazyPrimitive != null) {
                return loadLazyPrimitive(lazyPrimitive);
            }
        }

        return undefinedPrimitive;
    }

    public void addLazyPrimitive(String primitive, String nodeFactoryClass) {
        lazyPrimitiveClasses.put(primitive, nodeFactoryClass);
    }

    private PrimitiveNodeConstructor loadLazyPrimitive(String lazyPrimitive) {
        final NodeFactory<? extends RubyNode> nodeFactory = CoreMethodNodeManager.loadNodeFactory(lazyPrimitive);
        final Primitive annotation = nodeFactory.getNodeClass().getAnnotation(Primitive.class);
        return addPrimitive(nodeFactory, annotation);
    }

    public PrimitiveNodeConstructor addPrimitive(NodeFactory<? extends RubyNode> nodeFactory, Primitive annotation) {
        return ConcurrentOperations.getOrCompute(primitives, annotation.name(),
                k -> new PrimitiveNodeConstructor(annotation, nodeFactory));
    }
}
