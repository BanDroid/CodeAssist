/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tyron.builder.api.internal.artifacts.ivyservice.modulecache;

public class ModuleRepositoryCacheProvider {
    private final ModuleRepositoryCaches caches;
    private final ModuleRepositoryCaches inMemoryCaches;
    private final ResolvedArtifactCaches resolvedArtifactCaches = new ResolvedArtifactCaches();

    public ModuleRepositoryCacheProvider(ModuleRepositoryCaches caches, ModuleRepositoryCaches inMemoryCaches) {
        this.caches = caches;
        this.inMemoryCaches = inMemoryCaches;
    }

    /**
     * Returns caches which will also be persisted to disk. They will also have an in-memory
     * front-end, but eventually all results are persisted.
     */
    public ModuleRepositoryCaches getPersistentCaches() {
        return caches;
    }

    /**
     * Returns caches which are *only* in memory: they will never write anything to disk.
     */
    public ModuleRepositoryCaches getInMemoryOnlyCaches() {
        return inMemoryCaches;
    }

    public ResolvedArtifactCaches getResolvedArtifactCaches() {
        return resolvedArtifactCaches;
    }
}