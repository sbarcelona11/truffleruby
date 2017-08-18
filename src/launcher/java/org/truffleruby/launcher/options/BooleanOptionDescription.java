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

import org.graalvm.options.OptionType;

public class BooleanOptionDescription extends OptionDescription<Boolean> {

    private final boolean defaultValue;

    BooleanOptionDescription(String name, String description, String[] rubyOptions, boolean defaultValue) {
        super(name, description, rubyOptions);
        this.defaultValue = defaultValue;
    }

    @Override
    public Boolean getDefaultValue() {
        return defaultValue;
    }

    @Override
    public Boolean checkValue(Object value) {
        if (value == null) {
            return false;
        } else if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof String) {
            switch ((String) value) {
                case "true":
                    return true;
                case "false":
                    return false;
                default:
                    throw new OptionTypeException(getName(), value.toString());
            }
        } else {
            throw new OptionTypeException(getName(), value.toString());
        }
    }

    @Override
    protected OptionType<Boolean> getOptionType() {
        return OptionType.defaultType(true);
    }

}
