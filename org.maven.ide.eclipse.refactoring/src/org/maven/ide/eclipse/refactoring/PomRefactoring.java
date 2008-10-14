package org.maven.ide.eclipse.refactoring;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.emf.common.command.BasicCommandStack;
import org.eclipse.emf.common.command.Command;
import org.eclipse.emf.common.notify.impl.AdapterFactoryImpl;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.edit.domain.AdapterFactoryEditingDomain;
import org.eclipse.emf.edit.provider.ComposedAdapterFactory;
import org.eclipse.emf.edit.provider.ReflectiveItemProviderAdapterFactory;
import org.eclipse.emf.edit.provider.resource.ResourceItemProviderAdapterFactory;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.DocumentChange;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.maven.ide.components.pom.Model;
import org.maven.ide.eclipse.MavenPlugin;
import org.maven.ide.eclipse.embedder.MavenModelManager;
import org.maven.ide.eclipse.project.IMavenProjectFacade;

/**
 * Base class for all pom.xml refactorings in workspace
 * 
 * @author Anton Kraev
 *
 */
public abstract class PomRefactoring extends Refactoring {

  //main file that is being refactored
  protected IFile file;
  
  //file buffer manager
  protected ITextFileBufferManager textFileBufferManager;

  //maven plugin
  MavenPlugin mavenPlugin;

  //maven model manager
  protected MavenModelManager mavenModelManager;
  
  //refactored files in workspace
  protected HashMap<IFile, List<EObject>> refactored;

  //editing domain
  private AdapterFactoryEditingDomain editingDomain;

  public PomRefactoring(IFile file) {
    this.file = file;
    
    textFileBufferManager = FileBuffers.getTextFileBufferManager();
    mavenPlugin = MavenPlugin.getDefault();
    mavenModelManager = MavenPlugin.getDefault().getMavenModelManager();
    refactored = new HashMap<IFile,List<EObject>>();
    List<AdapterFactoryImpl> factories = new ArrayList<AdapterFactoryImpl>();
    factories.add(new ResourceItemProviderAdapterFactory());
    factories.add(new ReflectiveItemProviderAdapterFactory());

    ComposedAdapterFactory adapterFactory = new ComposedAdapterFactory(factories);
    BasicCommandStack commandStack = new BasicCommandStack();
    editingDomain = new AdapterFactoryEditingDomain(adapterFactory, //
        commandStack, new HashMap<Resource, Boolean>());
  }

  //this gets actual refactoring visitor
  public abstract PomVisitor getVisitor();
  
  @Override
  public RefactoringStatus checkFinalConditions(IProgressMonitor pm) throws CoreException, OperationCanceledException {
    //calculate the list of affected files
    RefactoringStatus status = new RefactoringStatus();
    IMavenProjectFacade[] res = mavenPlugin.getMavenProjectManager().getProjects();
    for (int i=0; i<res.length; i++) {
      try {
        IFile file = res[i].getPom();
        Model current = mavenModelManager.loadResource(file).getModel();
        List<EObject> affected = getVisitor().scanModel(file, current);
        if (!affected.isEmpty()) {
          refactored.put(res[i].getPom(), affected);
        }
      } catch(CoreException e) {
        status.addError(e.getMessage());
      }
    }
    return status;
  }

  @Override
  public Change createChange(IProgressMonitor pm) throws CoreException, OperationCanceledException {
    CompositeChange res = new CompositeChange("Renaming " + file.getParent().getName());
    Iterator<IFile> files = refactored.keySet().iterator();
    while (files.hasNext()) {
      IFile file = files.next();
      ITextFileBuffer buffer = getBuffer(file);
      String before = buffer.getDocument().get();
      Command command = getVisitor().applyModel(editingDomain, refactored.get(file));
      editingDomain.getCommandStack().execute(command);
      String after = buffer.getDocument().get();
      editingDomain.getCommandStack().undo();
      //TODO: files are still dirty after this... try IStructuredDocument?
      //because after undo, command can be redone. need to clean
      //maybe there is special "releaseBuffer" call?
      releaseBuffer(file);
      DocumentChange change = new ChangeCreator(buffer.getDocument(), file.getParent().getName(), before, after).createChange();
      res.add(change);
    }
    return res;
  }

  protected ITextFileBuffer getBuffer(IFile file) throws CoreException {
    textFileBufferManager.connect(file.getLocation(), LocationKind.NORMALIZE, null);
    return textFileBufferManager.getTextFileBuffer(file.getLocation(), LocationKind.NORMALIZE); 
  }

  protected void releaseBuffer(IFile file) throws CoreException {
    textFileBufferManager.disconnect(file.getLocation(), LocationKind.NORMALIZE, null);
  }
  
  //TODO: modelManager.loadResource(file) should be cached!!!
  public Model getModel() {
    try {
      return mavenModelManager.loadResource(file).getModel();
    } catch(CoreException e) {
      return null;
    }
  }

  protected IFile getFile() {
    return file;
  }
}
