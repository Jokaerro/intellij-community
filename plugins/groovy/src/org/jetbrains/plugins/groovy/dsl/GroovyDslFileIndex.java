/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.plugins.groovy.dsl;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentAdapter;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.UserDataCache;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.FileAttribute;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.scope.PsiScopeProcessor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.*;
import com.intellij.unscramble.UnscrambleDialog;
import com.intellij.util.ProcessingContext;
import com.intellij.util.containers.ConcurrentFactoryMap;
import com.intellij.util.containers.ConcurrentHashMap;
import com.intellij.util.containers.ConcurrentMultiMap;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.indexing.*;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import groovy.lang.Closure;
import org.codehaus.groovy.runtime.InvokerInvocationException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.dsl.holders.CustomMembersHolder;
import org.jetbrains.plugins.groovy.dsl.toplevel.ClassContextFilter;
import org.jetbrains.plugins.groovy.dsl.toplevel.ContextFilter;
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression;

import javax.swing.event.HyperlinkEvent;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * @author peter
 */
public class GroovyDslFileIndex extends ScalarIndexExtension<String> {
  public static final Key<CachedValue<ConcurrentFactoryMap<ClassDescriptor, CustomMembersHolder>>> CACHED_ENHANCEMENTS =
    Key.create("CACHED_ENHANCEMENTS");
  private static final Logger LOG = Logger.getInstance("#org.jetbrains.plugins.groovy.dsl.GroovyDslFileIndex");
  private static final FileAttribute ENABLED = new FileAttribute("ENABLED", 0);

  @NonNls public static final ID<String, Void> NAME = ID.create("GroovyDslFileIndex");
  @NonNls private static final String OUR_KEY = "ourKey";
  private final MyDataIndexer myDataIndexer = new MyDataIndexer();
  private final MyInputFilter myInputFilter = new MyInputFilter();

  private static final Map<String, Pair<GroovyDslExecutor, Long>> ourMapping =
    new ConcurrentHashMap<String, Pair<GroovyDslExecutor, Long>>();
  private static final MultiMap<String, LinkedBlockingQueue<Pair<GroovyFile, GroovyDslExecutor>>> filesInProcessing =
    new ConcurrentMultiMap<String, LinkedBlockingQueue<Pair<GroovyFile, GroovyDslExecutor>>>();

  private final EnumeratorStringDescriptor myKeyDescriptor = new EnumeratorStringDescriptor();
  private static final byte[] ENABLED_FLAG = new byte[]{(byte)239};

  public ID<String, Void> getName() {
    return NAME;
  }

  public DataIndexer<String, Void, FileContent> getIndexer() {
    return myDataIndexer;
  }

  public KeyDescriptor<String> getKeyDescriptor() {
    return myKeyDescriptor;
  }

  public FileBasedIndex.InputFilter getInputFilter() {
    return myInputFilter;
  }

  public boolean dependsOnFileContent() {
    return false;
  }

  public int getVersion() {
    return 0;
  }

  public static boolean isActivated(VirtualFile file) {
    try {
      final byte[] bytes = ENABLED.readAttributeBytes(file);
      if (bytes == null) {
        return true;
      }

      return bytes.length == ENABLED_FLAG.length && bytes[0] == ENABLED_FLAG[0];
    }
    catch (IOException e) {
      return false;
    }
  }

  public static void activateUntilModification(final VirtualFile vfile) {
    final Document document = FileDocumentManager.getInstance().getDocument(vfile);
    if (document != null) {
      document.addDocumentListener(new DocumentAdapter() {
        @Override
        public void beforeDocumentChange(DocumentEvent e) {
          disableFile(vfile);
          document.removeDocumentListener(this);
        }
      });
    }

    try {
      ENABLED.writeAttributeBytes(vfile, ENABLED_FLAG);
    }
    catch (IOException e) {
      LOG.error(e);
    }
  }

  private static void disableFile(final VirtualFile vfile) {
    try {
      ENABLED.writeAttributeBytes(vfile, new byte[0]);
    }
    catch (IOException e1) {
      LOG.error(e1);
    }
    ourMapping.remove(vfile.getUrl());
  }


  @Nullable
  private static GroovyDslExecutor getCachedExecutor(@NotNull final VirtualFile file, final long stamp) {
    final Pair<GroovyDslExecutor, Long> pair = ourMapping.get(file.getUrl());
    if (pair == null || pair.second.longValue() != stamp) {
      return null;
    }
    return pair.first;
  }

  //an absolutely guru code (c)
  public static boolean processExecutors(@NotNull Project project, ClassDescriptor descriptor, PsiScopeProcessor processor) {
    final PsiElement place = descriptor.getPlace();
    if (!(place instanceof GrReferenceExpression) || PsiTreeUtil.getParentOfType(place, PsiAnnotation.class) != null) {
      // Basic filter, all DSL contexts are applicable for reference expressions only
      return true;
    }

    final LinkedBlockingQueue<Pair<GroovyFile, GroovyDslExecutor>> queue = new LinkedBlockingQueue<Pair<GroovyFile, GroovyDslExecutor>>();

    int count = queueExecutors(project, queue);

    try {
      while (count > 0) {
        ProgressManager.checkCanceled();
        final Pair<GroovyFile, GroovyDslExecutor> pair = queue.poll(20, TimeUnit.MILLISECONDS);
        if (pair != null) {
          final GroovyDslExecutor executor = pair.second;
          final GroovyFile dslFile = pair.first;
          if (executor != null && !processExecutor(executor, descriptor, processor, dslFile)) {
            return false;
          }

          count--;
        }
      }
    }
    catch (InterruptedException e) {
      LOG.error(e);
    }

    return true;
  }

  private static final UserDataCache<CachedValue<List<GroovyFile>>, Project, Object> DSL_FILES_CACHE = new UserDataCache<CachedValue<List<GroovyFile>>, Project, Object>("DSL_FILES_CACHE") {
    @Override
    protected CachedValue<List<GroovyFile>> compute(final Project project, Object p) {
      return CachedValuesManager.getManager(project).createCachedValue(new CachedValueProvider<List<GroovyFile>>() {
        public Result<List<GroovyFile>> compute() {
          final AdditionalIndexableFileSet standardSet = new AdditionalIndexableFileSet(StandardDslIndexedRootsProvider.getInstance());
          final ProjectFileIndex fileIndex = ProjectRootManager.getInstance(project).getFileIndex();

          final AdditionalIndexedRootsScope scope = new AdditionalIndexedRootsScope(GlobalSearchScope.allScope(project), standardSet);
          List<GroovyFile> result = new ArrayList<GroovyFile>();
          for (VirtualFile vfile : FileBasedIndex.getInstance().getContainingFiles(NAME, OUR_KEY, scope)) {
            if (!vfile.isValid()) {
              continue;
            }
            if (!standardSet.isInSet(vfile) && !fileIndex.isInLibraryClasses(vfile) && !fileIndex.isInLibrarySource(vfile)) {
              if (!fileIndex.isInSourceContent(vfile) || !isActivated(vfile)) {
                continue;
              }
            }
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vfile);
            if (!(psiFile instanceof GroovyFile)) {
              continue;
            }
            GroovyFile file = (GroovyFile)psiFile;
            result.add(file);
          }
          return Result.create(result, PsiModificationTracker.MODIFICATION_COUNT, ProjectRootManager.getInstance(project));
        }
      }, false);
    }
  };

  private static int queueExecutors(Project project, LinkedBlockingQueue<Pair<GroovyFile, GroovyDslExecutor>> queue) {
    int count = 0;
    for (GroovyFile file : DSL_FILES_CACHE.get(project, null).getValue()) {
      final long stamp = file.getModificationStamp();
      final VirtualFile vfile = file.getVirtualFile();
      assert vfile != null;
      final GroovyDslExecutor cached = getCachedExecutor(vfile, stamp);
      count++;
      if (cached == null) {
        file.putUserData(CACHED_ENHANCEMENTS, null); //otherwise an old executor instance will be executed inside cachedValue
        scheduleParsing(queue, file, vfile, stamp, file.getText());
      }
      else {
        queue.offer(Pair.create(file, cached));
      }
    }
    return count;
  }


  private static boolean processExecutor(final GroovyDslExecutor executor,
                                         final ClassDescriptor descriptor,
                                         PsiScopeProcessor processor,
                                         final GroovyFile dslFile) {
    final Project project = dslFile.getProject();
    final ConcurrentFactoryMap<ClassDescriptor, CustomMembersHolder> map = CachedValuesManager.getManager(dslFile.getProject()).getCachedValue(dslFile, CACHED_ENHANCEMENTS, new CachedValueProvider<ConcurrentFactoryMap<ClassDescriptor, CustomMembersHolder>>() {
        public Result<ConcurrentFactoryMap<ClassDescriptor, CustomMembersHolder>> compute() {
          final ConcurrentFactoryMap<ClassDescriptor, CustomMembersHolder> result =
            new ConcurrentFactoryMap<ClassDescriptor, CustomMembersHolder>() {
              @Override
              protected CustomMembersHolder create(ClassDescriptor key) {
                final PsiElement place = key.getPlace();
                final String fqn = key.getQualifiedName();

                final ProcessingContext ctx = new ProcessingContext();
                ctx.put(ClassContextFilter.getClassKey(fqn), ((GroovyClassDescriptor)key).getPsiClass());
                try {
                  if (!isApplicable(place, fqn, ctx)) {
                    return null;
                  }

                  final CustomMembersGenerator generator = new CustomMembersGenerator(project, place, fqn);

                  executor.processVariants(key, generator, place, fqn, ctx);
                  return generator.getMembersHolder();
                }
                catch (InvokerInvocationException e) {
                  Throwable cause = e.getCause();
                  if (cause instanceof ProcessCanceledException) {
                    throw (ProcessCanceledException)cause;
                  }
                  handleDslError(e, project, dslFile);
                }
                catch (ProcessCanceledException e) {
                  throw e;
                }
                catch (Throwable e) { // To handle exceptions in definition script
                  handleDslError(e, project, dslFile);
                }
                return null;
              }

              private boolean isApplicable(PsiElement place, String fqn, final ProcessingContext ctx) {
                for (Pair<ContextFilter, Closure> pair : executor.getEnhancers()) {
                  if (pair.first.isApplicable(place, fqn, ctx)) {
                    return true;
                  }
                }
                return false;
              }

            };
          return Result.create(result, PsiModificationTracker.MODIFICATION_COUNT, dslFile);
        }
      }, false);
    assert map != null;
    final CustomMembersHolder holder = map.get(descriptor);
    return holder == null || holder.processMembers(processor);
  }

  private static boolean handleDslError(Throwable e, Project project, GroovyFile dslFile) {
    if (project.isDisposed() || ApplicationManager.getApplication().isUnitTestMode()) {
      LOG.error(e);
      return true;
    }
    invokeDslErrorPopup(e, project, dslFile.getVirtualFile());
    return false;
  }

  private static class MyDataIndexer implements DataIndexer<String, Void, FileContent> {

    @NotNull
    public Map<String, Void> map(final FileContent inputData) {
      return Collections.singletonMap(OUR_KEY, null);
    }
  }

  private static class MyInputFilter implements FileBasedIndex.InputFilter {
    public boolean acceptInput(final VirtualFile file) {
      return "gdsl".equals(file.getExtension());
    }
  }

  private static void scheduleParsing(final LinkedBlockingQueue<Pair<GroovyFile, GroovyDslExecutor>> queue,
                                      final GroovyFile file,
                                      final VirtualFile vfile,
                                      final long stamp,
                                      final String text) {
    final Project project = file.getProject();
    final String fileUrl = vfile.getUrl();

    final Runnable parseScript = new Runnable() {
      public void run() {
        GroovyDslExecutor executor = getCachedExecutor(vfile, stamp);
        if (executor == null) {
          executor = createExecutor(text, vfile, project);
          // executor is not only time-consuming to create, but also takes some PermGenSpace
          // => we can't afford garbage-collecting it together with PsiFile
          // => cache globally by file path
          ourMapping.put(vfile.getUrl(), Pair.create(executor, stamp));
          if (executor != null) {
            activateUntilModification(vfile);
          }
        }

        // access to our multimap should be synchronized
        synchronized (vfile) {
          // put evaluated executor to all queues
          final Collection<LinkedBlockingQueue<Pair<GroovyFile, GroovyDslExecutor>>> queuesForFile = filesInProcessing.remove(fileUrl);
          for (LinkedBlockingQueue<Pair<GroovyFile, GroovyDslExecutor>> queue : queuesForFile) {
            queue.offer(Pair.create(file, executor));
          }
        }
      }
    };

    synchronized (vfile) { //ensure that only one thread calculates dsl executor
      final boolean isNewRequest = !filesInProcessing.containsKey(fileUrl);
      filesInProcessing.putValue(fileUrl, queue);
      if (isNewRequest) {
        ApplicationManager.getApplication().executeOnPooledThread(parseScript);
      }
    }
  }

  @Nullable
  private static GroovyDslExecutor createExecutor(String text, VirtualFile vfile, final Project project) {
    try {
      return new GroovyDslExecutor(text, vfile.getName());
    }
    catch (final Throwable e) {
      if (project.isDisposed()) {
        LOG.error(e);
        return null;
      }

      if (ApplicationManager.getApplication().isUnitTestMode()) {
        try {
          LOG.error(e);
        }
        finally {
          return null;
        }
      }
      invokeDslErrorPopup(e, project, vfile);
      return null;
    }
  }
  private static void invokeDslErrorPopup(Throwable e, final Project project, VirtualFile vfile) {
    final StringWriter writer = new StringWriter();
    e.printStackTrace(new PrintWriter(writer));

    ApplicationManager.getApplication().getMessageBus().syncPublisher(Notifications.TOPIC).notify(
      new Notification("Groovy DSL parsing", "DSL script execution error",
                       "<p>" + e.getMessage() + "</p><p><a href=\"\">Click here to investigate.</a></p>", NotificationType.ERROR,
                       new NotificationListener() {
                         public void hyperlinkUpdate(@NotNull Notification notification, @NotNull HyperlinkEvent event) {
                           final UnscrambleDialog dialog = new UnscrambleDialog(project);
                           dialog.setText(writer.toString());
                           dialog.show();
                           notification.expire();
                         }
                       }));

    disableFile(vfile);
  }

}
