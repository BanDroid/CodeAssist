package com.tyron.completion.java.parse;

import androidx.annotation.NonNull;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Scope;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.comp.MemberEnter;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.Pair;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.common.util.DebouncerStore;
import com.tyron.completion.java.compiler.services.NBEnter;
import com.tyron.completion.java.compiler.services.NBLog;

import org.jetbrains.kotlin.com.intellij.openapi.util.Key;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.JavaFileObject;

public class CompilationInfo {

    public static final Key<CompilationInfo> COMPILATION_INFO_KEY = Key.create("compilationInfo");

    public static CompilationInfo get(Project currentProject, File file) {
        final Module module = currentProject.getModule(file);
        if (!(module instanceof JavaModule)) {
            return null;
        }
        JavaModule javaModule = (JavaModule) module;
        CompilationInfo info = module.getUserData(COMPILATION_INFO_KEY);
        if (info == null) {
            info = new CompilationInfo(new CompilationInfoImpl(
                    new JavacParser(),
                    file,
                    file,
                    javaModule.getLibraries(),
                    Collections.emptyList(),
                    null,
                    null
            ));
            module.putUserData(COMPILATION_INFO_KEY, info);
        }
        return info;
    }

    public final CompilationInfoImpl impl;
    private final Map<URI, JCCompilationUnit> compiledMap = new HashMap<>();

    private final DebouncerStore<String> debouncerStore = DebouncerStore.DEFAULT;

    private final Object parseLock = new Object();
    private Trees trees;

    public CompilationInfo(final CompilationInfoImpl impl) {
        assert impl != null;
        this.impl = impl;
    }

    public JCCompilationUnit updateImmediately(JavaFileObject fileObject) {
        CompletableFuture<JCCompilationUnit> future = new CompletableFuture<>();
        update(fileObject, 0, future::complete);
        try {
            return future.get();
        } catch (ExecutionException | InterruptedException e) {
            return null;
        }
    }

    public void update(JavaFileObject fileObject) {
        this.update(fileObject, 300, __ -> {
        });
    }

    public void update(JavaFileObject fileObject, long delay) {
        this.update(fileObject, delay, __ -> {
        });
    }

    public synchronized void update(JavaFileObject fileObject,
                                    long delay,
                                    Consumer<JCCompilationUnit> treeConsumer) {
        debouncerStore.registerOrGetDebouncer("update").debounce(delay, () -> {
            synchronized (parseLock) {
                try {
                    JavacTaskImpl javacTask = impl.getJavacTask();

                    NBLog log = NBLog.instance(javacTask.getContext());
                    log.useSource(fileObject);

                    Set<Pair<JavaFileObject, Integer>> toRemove = new HashSet<>();
                    for (Pair<JavaFileObject, Integer> pair : log.getRecorded()) {
                        if (pair.fst.toUri().equals(fileObject.toUri())) {
                            toRemove.add(pair);
                        }
                    }
                    log.getRecorded().removeAll(toRemove);
                    log.removeDiagnostics(fileObject.toUri());


                    JCCompilationUnit previous = compiledMap.get(fileObject.toUri());
                    if (previous != null) {
                        NBEnter enter = (NBEnter) NBEnter.instance(javacTask.getContext());
                        enter.unenter(previous, previous);
                        enter.removeCompilationUnit(fileObject);
                    }


                    // reparse the whole file
                    Iterable<? extends CompilationUnitTree> units;
                    try {
                        units = javacTask.parse(fileObject);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                    if (previous != null) {
                        ((JCCompilationUnit) units.iterator().next()).toplevelScope = previous.toplevelScope;
                    }

                    if (!units.iterator().hasNext()) {
                        return;
                    }
                    javacTask.analyze();

                    JCCompilationUnit newUnit = (JCCompilationUnit) units.iterator().next();
                    compiledMap.put(fileObject.toUri(), newUnit);

                    treeConsumer.accept((JCCompilationUnit) units.iterator().next());
                } catch (Throwable t) {
                    System.out.println(t);
                    treeConsumer.accept(null);
                }
            }
        });
    }

    public JCCompilationUnit getCompilationUnit(JavaFileObject fileObject) {
        return getCompilationUnit(fileObject.toUri());
    }

    public JCCompilationUnit getCompilationUnit(URI uri) {
        return compiledMap.get(uri);
    }


    public List<? extends TypeElement> getTopLevelElements() throws IllegalStateException {
        return null;
//        if (this.impl.getFileObject() == null) {
//            throw new IllegalStateException ();
//        }
//        final List<TypeElement> result = new ArrayList<TypeElement>();
//        if (this.impl.isClassFile()) {
//            final JavacElements elements = (JavacElements) getElements();
//            assert elements != null;
//            assert this.impl.getRoot() != null;
//            final String name = FileObjects.convertFolder2Package(FileObjects.stripExtension(
//                    FileUtil.getRelativePath(this.impl.getRoot(), this.impl.getFileObject())));
//            final TypeElement e = Optional.ofNullable(
//                            SourceVersion.RELEASE_9.compareTo(getSourceVersion()) <= 0 ?
//                                    SourceUtils.getModuleName(impl.getRoot().toURL(), true) :
//                                    null)
//                    .map(elements::getModuleElement)
//                    .map((module) -> ElementUtils.getTypeElementByBinaryName(this, module, name))
//                    .orElseGet(() -> ElementUtils.getTypeElementByBinaryName(this, name));
//            if (e != null) {
//                result.add (e);
//            }
//        } else {
//            CompilationUnitTree cu = getCompilationUnit();
//            if (cu == null) {
//                return null;
//            }
//            else {
//                final Trees ts = getTrees();
//                assert ts != null;
//                List<? extends Tree> typeDecls = cu.getTypeDecls();
//                TreePath cuPath = new TreePath(cu);
//                for( Tree t : typeDecls ) {
//                    TreePath p = new TreePath(cuPath,t);
//                    Element e = ts.getElement(p);
//                    if ( e != null && ( e.getKind().isClass() || e.getKind().isInterface() ) ) {
//                        result.add((TypeElement)e);
//                    }
//                }
//            }
//        }
//        return Collections.unmodifiableList(result);
    }

    /**
     * Return the {@link Trees} service of the javac represented by this {@link CompilationInfo}.
     *
     * @return javac Trees service
     */
    public synchronized @NonNull
    Trees getTrees() {
        if (trees == null) {
            //use a working init order:
            com.sun.tools.javac.main.JavaCompiler.instance(impl.getJavacTask().getContext());
            trees = JavacTrees.instance(impl.getJavacTask().getContext());
        }
        return trees;
    }

    /**
     * Return the {@link Elements} service of the javac represented by this {@link CompilationInfo}.
     *
     * @return javac Elements service
     */
    public @NonNull
    Elements getElements() {
        //use a working init order:
        com.sun.tools.javac.main.JavaCompiler.instance(impl.getJavacTask().getContext());
        return impl.getJavacTask().getElements();
    }

    public File getFileObject() {
//        return impl.getFileObject();
        throw new UnsupportedOperationException();
    }

    public CompilationUnitTree getCompilationUnit() {
        return impl.getCompilationUnit();
    }
}
