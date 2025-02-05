/*******************************************************************************
 * Copyright (c) 2019 Red Hat Inc. and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Mickael Istria (Red Hat Inc.) - Initial implementation
 *******************************************************************************/
package org.eclipse.wildwebdeveloper.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.ISuspendResume;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.debug.internal.core.LaunchManager;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.actions.IToggleBreakpointsTarget;
import org.eclipse.jface.dialogs.MessageDialogWithToggle;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IOConsole;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.eclipse.ui.tests.harness.util.DisplayHelper;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.wildwebdeveloper.debug.node.NodeRunDAPDebugDelegate;
import org.eclipse.wildwebdeveloper.debug.node.NodeRunDebugLaunchShortcut;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@SuppressWarnings("restriction")
@ExtendWith(AllCleanRule.class)
public class TestDebug {

	protected ILaunchManager launchManager;

	@BeforeEach
	public void setUpLaunch() throws DebugException {
		this.launchManager = DebugPlugin.getDefault().getLaunchManager();
		removeAllLaunches();
		ScopedPreferenceStore prefs = new ScopedPreferenceStore(InstanceScope.INSTANCE, "org.eclipse.debug.ui");
		prefs.setValue("org.eclipse.debug.ui.switch_perspective_on_suspend", MessageDialogWithToggle.ALWAYS);
	}

	private void removeAllLaunches() throws DebugException {
		for (ILaunch launch : this.launchManager.getLaunches()) {
			launch.terminate();
			for (IDebugTarget debugTarget : launch.getDebugTargets()) {
				debugTarget.terminate();
				launch.removeDebugTarget(debugTarget);
			}
			launchManager.removeLaunch(launch);
		}
	}

	@AfterEach
	public void tearDownLaunch() throws DebugException {
		removeAllLaunches();
	}

	@Test
	public void testRunExpandEnv() throws Exception {
		File f = File.createTempFile("testEnv", ".js");
		f.deleteOnExit();
		Files.write(f.toPath(), "console.log(process.env.ECLIPSE_HOME);".getBytes());
		ILaunchConfigurationWorkingCopy launchConfig = launchManager
				.getLaunchConfigurationType(NodeRunDAPDebugDelegate.ID)
				.newInstance(ResourcesPlugin.getWorkspace().getRoot(), f.getName());
		launchConfig.setAttribute(NodeRunDAPDebugDelegate.PROGRAM, f.getAbsolutePath());
		launchConfig.setAttribute(LaunchManager.ATTR_ENVIRONMENT_VARIABLES, Map.of("ECLIPSE_HOME", "${eclipse_home}"));
		launchConfig.setAttribute(DebugPlugin.ATTR_CAPTURE_OUTPUT, true);
		ILaunch launch = launchConfig.launch(ILaunchManager.RUN_MODE, new NullProgressMonitor());
		while (!launch.isTerminated()) {
			DisplayHelper.sleep(Display.getDefault(), 50);
		}
		// ensure last UI events are processed and console is visible and populated.
		assertFalse(
				DisplayHelper.waitForCondition(Display.getDefault(), 1000,
						() -> Arrays.stream(ConsolePlugin.getDefault().getConsoleManager().getConsoles()) //
								.filter(IOConsole.class::isInstance) //
								.map(IOConsole.class::cast) //
								.map(IOConsole::getDocument) //
								.map(IDocument::get) //
								.anyMatch(content -> content.contains("${eclipse_home}"))),
				"env variable is not replaced in subprocess");
	}

	@Test
	public void testFindThreadsAndHitsBreakpoint() throws Exception {
		IProject project = Utils.provisionTestProject("helloWorldJS");
		IFile jsFile = project.getFile("hello.js");
		ITextEditor editor = (ITextEditor) IDE
				.openEditor(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(), jsFile);
		IDocument doc = editor.getDocumentProvider().getDocument(editor.getEditorInput());
		TextSelection selection = new TextSelection(doc, doc.getLineOffset(1) + 1, 0);
		IToggleBreakpointsTarget toggleBreakpointsTarget = DebugUITools.getToggleBreakpointsTargetManager()
				.getToggleBreakpointsTarget(editor, selection);
		toggleBreakpointsTarget.toggleLineBreakpoints(editor, selection);
		Set<IDebugTarget> before = new HashSet<>(Arrays.asList(launchManager.getDebugTargets()));
		DisplayHelper.sleep(1000);
		new NodeRunDebugLaunchShortcut().launch(editor, ILaunchManager.DEBUG_MODE);
		assertTrue(new DisplayHelper() {
			@Override
			public boolean condition() {
				return launchManager.getDebugTargets().length > before.size();
			}
		}.waitForCondition(Display.getDefault(), 30000), "New Debug Target not created");
		Set<IDebugTarget> after = new HashSet<>(Arrays.asList(launchManager.getDebugTargets()));
		after.removeAll(before);
		assertEquals(1, after.size(), "Extra DebugTarget not found");
		IDebugTarget target = after.iterator().next();
		assertTrue(new DisplayHelper() {
			@Override
			public boolean condition() {
				try {
					return target.getThreads().length > 0;
				} catch (DebugException e) {
					e.printStackTrace();
					return false;
				}
			}
		}.waitForCondition(Display.getDefault(), 30000), "Debug Target shows no threads");
		assertTrue(new DisplayHelper() {
			@Override
			public boolean condition() {
				try {
					return Arrays.stream(target.getThreads()).anyMatch(ISuspendResume::isSuspended);
				} catch (DebugException e) {
					e.printStackTrace();
					return false;
				}
			}
		}.waitForCondition(Display.getDefault(), 3000), "No thread is suspended");
		IThread suspendedThread = Arrays.stream(target.getThreads()).filter(ISuspendResume::isSuspended).findFirst()
				.get();
		assertTrue(new DisplayHelper() {
			@Override
			protected boolean condition() {
				try {
					return suspendedThread.getStackFrames().length > 0
							&& suspendedThread.getStackFrames()[0].getVariables().length > 0;
				} catch (Exception ex) {
					// ignore
					return false;
				}
			}
		}.waitForCondition(Display.getDefault(), 3000), "Suspended Thread doesn't show variables");
		IVariable localVariable = suspendedThread.getStackFrames()[0].getVariables()[0];
		assertEquals("Local", localVariable.getName());
		IVariable nVariable = Arrays.stream(localVariable.getValue().getVariables()).filter(var -> {
			try {
				return "n".equals(var.getName());
			} catch (DebugException e) {
				return false;
			}
		}).findAny().get();
		assertEquals("1605", nVariable.getValue().getValueString());
	}
}
