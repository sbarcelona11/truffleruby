/*
 * Copyright (c) 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.launcher.options;

public class ExecutionActionOptionDescription extends EnumOptionDescription<ExecutionAction> {

    ExecutionActionOptionDescription(String name, String description, String[] rubyOptions, ExecutionAction defaultValue) {
        super(name, description, rubyOptions, defaultValue, ExecutionAction.class);
    }

}
