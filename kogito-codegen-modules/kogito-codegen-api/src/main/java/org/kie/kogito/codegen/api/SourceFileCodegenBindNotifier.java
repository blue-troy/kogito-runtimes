/*
 * Copyright 2022 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kie.kogito.codegen.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

public class SourceFileCodegenBindNotifier {

    private final Collection<SourceFileCodegenBindListener<?>> listeners = new ArrayList<>();

    public void addListeners(SourceFileCodegenBindListener<?>... listeners) {
        Collections.addAll(this.listeners, listeners);
    }

    @SuppressWarnings("unchecked")
    public <T extends SourceFileCodegenBindEvent> void notify(T event) {
        listeners.stream()
                .filter(listener -> listener.getEventType().isAssignableFrom(event.getClass()))
                .map(listener -> (SourceFileCodegenBindListener<T>) listener)
                .forEach(listener -> listener.onSourceFileCodegenBind(event));
    }
}
