/*
 * Copyright (c) 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.launcher.options;

import org.graalvm.options.OptionDescriptor;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionType;

public abstract class OptionDescription<T> {

    private final String name;
    private final String description;

    public OptionDescription(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public abstract T getDefaultValue();

    public abstract T checkValue(Object value);

    @SuppressWarnings("unchecked")
    public T cast(Object value) {
        return (T) value;
    }

    public String valueToString(Object value) {
        if (value == null) {
            return "null";
        } else {
            return value.toString();
        }
    }

    public OptionDescriptor toDescriptor() {
        return OptionDescriptor.newBuilder(new OptionKey<>(getDefaultValue(), getOptionType()), getName()).help(getDescription()).build();
    }

    protected abstract OptionType<T> getOptionType();

}
