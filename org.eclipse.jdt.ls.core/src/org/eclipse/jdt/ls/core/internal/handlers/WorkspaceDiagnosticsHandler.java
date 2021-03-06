/*******************************************************************************
 * Copyright (c) 2016-2017 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.handlers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.JavaClientConnection;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.ResourceUtils;
import org.eclipse.jdt.ls.core.internal.managers.ProjectsManager;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.m2e.core.internal.IMavenConstants;
import org.eclipse.m2e.core.internal.Messages;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

/**
 * Listens to the resource change events and converts {@link IMarker}s to {@link Diagnostic}s.
 *
 * @author Gorkem Ercan
 *
 */
@SuppressWarnings("restriction")
public final class WorkspaceDiagnosticsHandler implements IResourceChangeListener, IResourceDeltaVisitor {

	public static final String PROJECT_CONFIGURATION_IS_NOT_UP_TO_DATE_WITH_POM_XML = "Project configuration is not up-to-date with pom.xml, requires an update.";
	private final JavaClientConnection connection;
	private final ProjectsManager projectsManager;

	public WorkspaceDiagnosticsHandler(JavaClientConnection connection, ProjectsManager projectsManager) {
		this.connection = connection;
		this.projectsManager = projectsManager;
	}

	public void addResourceChangeListener() {
		ResourcesPlugin.getWorkspace().addResourceChangeListener(this, IResourceChangeEvent.POST_CHANGE);
	}

	public void removeResourceChangeListener() {
		ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
	}

	@Override
	public void resourceChanged(IResourceChangeEvent event) {
		try {
			IResourceDelta delta = event.getDelta();
			delta.accept(this);
		} catch (CoreException e) {
			JavaLanguageServerPlugin.logException("failed to send diagnostics", e);
		}

	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * org.eclipse.core.resources.IResourceDeltaVisitor#visit(org.eclipse.core.
	 * resources.IResourceDelta)
	 */
	@Override
	public boolean visit(IResourceDelta delta) throws CoreException {
		IResource resource = delta.getResource();
		// Check if resource is accessible.
		// We do not deal with the markers for deleted files here
		// WorkspaceEventsHandler removes the diagnostics for deleted resources.
		if (resource == null || !resource.isAccessible()) {
			return false;
		}
		if (resource.getType() == IResource.FOLDER || resource.getType() == IResource.ROOT) {
			return true;
		}
		if (resource.getType() == IResource.PROJECT) {
			// ignore problems caused by standalone files (problems in the default project)
			if (JavaLanguageServerPlugin.getProjectsManager().getDefaultProject().equals(resource.getProject())) {
				return false;
			}
			// report problems for other projects
			IMarker[] markers = resource.findMarkers(null, true, IResource.DEPTH_ONE);
			Range range = new Range(new Position(0, 0), new Position(0, 0));

			Multimap<String, IMarker> allMarkers = HashMultimap.create(1, markers.length);

			String uri = JDTUtils.getFileURI(resource);
			for (IMarker marker : markers) {
				if (!marker.exists()) {
					continue;
				}
				if (IMavenConstants.MARKER_CONFIGURATION_ID.equals(marker.getType())) {
					String message = marker.getAttribute(IMarker.MESSAGE, "");
					if (Messages.ProjectConfigurationUpdateRequired.equals(message)) {
						allMarkers.put(uri + "/pom.xml", marker);
					}
				} else {
					allMarkers.put(uri, marker);
				}
			}
			for (Entry<String, Collection<IMarker>> e : allMarkers.asMap().entrySet()) {
				List<Diagnostic> diagnostics = toDiagnosticArray(range, e.getValue());
				connection.publishDiagnostics(new PublishDiagnosticsParams(ResourceUtils.toClientUri(e.getKey()), diagnostics));
			}
			return true;
		}
		// No marker changes continue to visit
		if ((delta.getFlags() & IResourceDelta.MARKERS) == 0) {
			return false;
		}
		IFile file = (IFile) resource;
		String uri = JDTUtils.getFileURI(resource);
		IDocument document = null;
		IMarker[] markers = null;
		// Check if it is a Java ...
		if (JavaCore.isJavaLikeFileName(file.getName())) {
			IMarker[] javaMarkers = resource.findMarkers(IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER, false, IResource.DEPTH_ONE);
			IMarker[] taskMarkers = resource.findMarkers(IJavaModelMarker.TASK_MARKER, false, IResource.DEPTH_ONE);
			markers = Arrays.copyOf(javaMarkers, javaMarkers.length + taskMarkers.length);
			System.arraycopy(taskMarkers, 0, markers, javaMarkers.length, taskMarkers.length);
			ICompilationUnit cu = (ICompilationUnit) JavaCore.create(file);
			document = JsonRpcHelpers.toDocument(cu.getBuffer());
		} // or a build file
		else if (projectsManager.isBuildFile(file)) {
			//all errors on that build file should be relevant
			markers = file.findMarkers(null, true, 1);
			document = JsonRpcHelpers.toDocument(file);
		}
		if (document != null) {
			this.connection.publishDiagnostics(new PublishDiagnosticsParams(ResourceUtils.toClientUri(uri), toDiagnosticsArray(document, markers)));
		}
		return false;
	}

	public List<IMarker> publishDiagnostics(IProgressMonitor monitor) throws CoreException {
		List<IMarker> problemMarkers = getProblemMarkers(monitor);
		publishDiagnostics(problemMarkers);
		return problemMarkers;
	}

	private List<IMarker> getProblemMarkers(IProgressMonitor monitor) throws CoreException {
		IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
		List<IMarker> markers = new ArrayList<>();
		for (IProject project : projects) {
			if (monitor != null && monitor.isCanceled()) {
				throw new OperationCanceledException();
			}
			markers.addAll(Arrays.asList(project.findMarkers(IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER, true, IResource.DEPTH_INFINITE)));
			markers.addAll(Arrays.asList(project.findMarkers(IJavaModelMarker.TASK_MARKER, true, IResource.DEPTH_INFINITE)));
		}
		return markers;
	}

	private void publishDiagnostics(List<IMarker> markers) {
		Map<IResource, List<IMarker>> map = markers.stream().collect(Collectors.groupingBy(IMarker::getResource));
		for (Map.Entry<IResource, List<IMarker>> entry : map.entrySet()) {
			IResource resource = entry.getKey();
			// ignore problems caused by standalone files
			if (JavaLanguageServerPlugin.getProjectsManager().getDefaultProject().equals(resource.getProject())) {
				continue;
			}
			if (resource instanceof IProject) {
				String uri = JDTUtils.getFileURI(resource);
				Range range = new Range(new Position(0, 0), new Position(0, 0));
				List<Diagnostic> diagnostics = WorkspaceDiagnosticsHandler.toDiagnosticArray(range, entry.getValue());
				connection.publishDiagnostics(new PublishDiagnosticsParams(ResourceUtils.toClientUri(uri), diagnostics));
				continue;
			}
			IFile file = resource.getAdapter(IFile.class);
			if (file == null) {
				continue;
			}
			IDocument document = null;
			String uri = JDTUtils.getFileURI(resource);
			if (JavaCore.isJavaLikeFileName(file.getName())) {
				ICompilationUnit cu = JDTUtils.resolveCompilationUnit(uri);
				try {
					document = JsonRpcHelpers.toDocument(cu.getBuffer());
				} catch (JavaModelException e) {
					JavaLanguageServerPlugin.logException("Failed to publish diagnostics.", e);
				}
			} else if (projectsManager.isBuildFile(file)) {
				document = JsonRpcHelpers.toDocument(file);
			}

			if (document != null) {
				List<Diagnostic> diagnostics = WorkspaceDiagnosticsHandler.toDiagnosticsArray(document, entry.getValue().toArray(new IMarker[0]));
				connection.publishDiagnostics(new PublishDiagnosticsParams(ResourceUtils.toClientUri(uri), diagnostics));
			}
		}
	}



	/**
	 * Transforms {@link IMarker}s into a list of {@link Diagnostic}s
	 *
	 * @param range
	 * @param markers
	 * @return a list of {@link Diagnostic}s
	 */
	public static List<Diagnostic> toDiagnosticArray(Range range, Collection<IMarker> markers) {
		List<Diagnostic> diagnostics = markers.stream().map(m -> toDiagnostic(range, m)).filter(d -> d != null).collect(Collectors.toList());
		return diagnostics;
	}

	private static Diagnostic toDiagnostic(Range range, IMarker marker) {
		if (marker == null || !marker.exists()) {
			return null;
		}
		Diagnostic d = new Diagnostic();
		d.setSource(JavaLanguageServerPlugin.SERVER_SOURCE_ID);
		String message = marker.getAttribute(IMarker.MESSAGE, "");
		if (Messages.ProjectConfigurationUpdateRequired.equals(message)) {
			message = PROJECT_CONFIGURATION_IS_NOT_UP_TO_DATE_WITH_POM_XML;
		}
		d.setMessage(message);
		d.setSeverity(convertSeverity(marker.getAttribute(IMarker.SEVERITY, -1)));
		d.setCode(String.valueOf(marker.getAttribute(IJavaModelMarker.ID, 0)));
		d.setRange(range);
		return d;
	}

	/**
	 * Transforms {@link IMarker}s of a {@link IDocument} into a list of
	 * {@link Diagnostic}s.
	 *
	 * @param document
	 * @param markers
	 * @return a list of {@link Diagnostic}s
	 */
	public static List<Diagnostic> toDiagnosticsArray(IDocument document, IMarker[] markers) {
		List<Diagnostic> diagnostics = Stream.of(markers)
				.map(m -> toDiagnostic(document, m))
				.filter(d -> d != null)
				.collect(Collectors.toList());
		return diagnostics;
	}

	private static Diagnostic toDiagnostic(IDocument document, IMarker marker) {
		if (marker == null || !marker.exists()) {
			return null;
		}
		Diagnostic d = new Diagnostic();
		d.setSource(JavaLanguageServerPlugin.SERVER_SOURCE_ID);
		d.setMessage(marker.getAttribute(IMarker.MESSAGE, ""));
		d.setCode(String.valueOf(marker.getAttribute(IJavaModelMarker.ID, 0)));
		d.setSeverity(convertSeverity(marker.getAttribute(IMarker.SEVERITY, -1)));
		d.setRange(convertRange(document, marker));
		return d;
	}

	/**
	 * @param marker
	 * @return
	 */
	private static Range convertRange(IDocument document, IMarker marker) {
		int line = marker.getAttribute(IMarker.LINE_NUMBER, -1) - 1;
		int cStart = 0;
		int cEnd = 0;
		try {
			//Buildship doesn't provide markers for gradle files, Maven does
			if (marker.isSubtypeOf(IMavenConstants.MARKER_ID)) {
				cStart = marker.getAttribute(IMavenConstants.MARKER_COLUMN_START, -1);
				cEnd = marker.getAttribute(IMavenConstants.MARKER_COLUMN_END, -1);
			} else {
				int lineOffset = 0;
				try {
					lineOffset = document.getLineOffset(line);
				} catch (BadLocationException unlikelyException) {
					JavaLanguageServerPlugin.logException(unlikelyException.getMessage(), unlikelyException);
					return new Range(new Position(line, 0), new Position(line, 0));
				}
				cEnd = marker.getAttribute(IMarker.CHAR_END, -1) - lineOffset;
				cStart = marker.getAttribute(IMarker.CHAR_START, -1) - lineOffset;
			}
		} catch (CoreException e) {
			JavaLanguageServerPlugin.logException(e.getMessage(), e);
		}
		cStart = Math.max(0, cStart);
		cEnd = Math.max(0, cEnd);

		return new Range(new Position(line, cStart), new Position(line, cEnd));
	}

	/**
	 * @param attribute
	 * @return
	 */
	private static DiagnosticSeverity convertSeverity(int severity) {
		if (severity == IMarker.SEVERITY_ERROR) {
			return DiagnosticSeverity.Error;
		}
		if (severity == IMarker.SEVERITY_WARNING) {
			return DiagnosticSeverity.Warning;
		}
		return DiagnosticSeverity.Information;
	}
}
