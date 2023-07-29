package dev.rg9.autotestfixer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.intellij.execution.ExecutionManager;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.testframework.sm.runner.SMTestProxy;
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;

public class AutoTestFixer extends AnAction {

	// TODO how to set log level to debug?
	//    <option name="vmOptions" value="-Didea.log.debug=true" />  in Run Plugin.run.xml doesn't work
	private static final Logger log = LoggerFactory.getLogger(AutoTestFixer.class);

	public static final Pattern MATCH_LINE_NUMBER = Pattern.compile("Test\\.java:(\\d+)");
	public static final Pattern EXPECTED_MATCHER = Pattern.compile("expected: ([^\n]+)");
	public static final Pattern ACTUAL_MATCHER = Pattern.compile("but was: ([^\n]+)");

	@Override
	public void actionPerformed(AnActionEvent e) {
		Project project = e.getProject();
		if (project == null) {
			return;
		}

		for (SMTestProxy testResult : testsFromSelectedExecutionConsole(project)) {

			var errorMessage = testResult.getErrorMessage();
			if (errorMessage != null) {
				var stacktrace = testResult.getStacktrace();
				System.out.println(testResult.getLocationUrl());
				System.out.println(errorMessage);
				System.out.println(stacktrace);
				System.out.println("---");

				int lineNumber = Arrays.stream(stacktrace.split("\n"))
					.flatMap(frame -> MATCH_LINE_NUMBER.matcher(frame).results().map(match -> match.group(1)))
					.findFirst()
					.map(Integer::parseInt)
					.orElse(-1)
					- 1;

				if (lineNumber < 0) {
					System.out.println("⚠️ Cannot find line number in stacktrace!!");
					continue;
				}

				Document document = testFile(project, testResult);
				if (document != null) {
					int lineStartOffset = document.getLineStartOffset(lineNumber);
					int lineEndOffset = document.getLineEndOffset(lineNumber);

					var text = document.getText();
					String lineText = text.substring(lineStartOffset, lineEndOffset);
					System.out.println("Failed line:");
					System.out.println(lineText);
					System.out.println("---");

					var expected = EXPECTED_MATCHER.matcher(errorMessage).results().map(m -> m.group(1)).findFirst().orElse(null);
					var actual = ACTUAL_MATCHER.matcher(errorMessage).results().map(m -> m.group(1)).findFirst().orElse(null);
					if (expected != null && actual != null) {
						System.out.println("Expected:");
						System.out.println(expected);
						System.out.println("Actual:");
						System.out.println(actual);
						System.out.println("---");

						var expectedStartIndex = text.indexOf(expected, lineStartOffset);
						WriteCommandAction.runWriteCommandAction(project, () ->
							document.replaceString(expectedStartIndex, expectedStartIndex + expected.length(), actual)
						);
						System.out.println("✅ Fixed.");
						rerunLastUsedRunConfiguration(project);
					} else {
						System.out.println("⚠️ Cannot match expected or actual !!");
						// TODO collect errors and show to user as popup?
					}

				}
			}
		}

	}

	private static Document testFile(Project project, SMTestProxy test) {
		PsiElement element = test.getLocation(project, GlobalSearchScope.projectScope(project)).getPsiElement();
		PsiFile file = element.getContainingFile();
		return PsiDocumentManager.getInstance(project).getDocument(file);
	}

	List<SMTestProxy> testsFromSelectedExecutionConsole(Project project) {
		var executionConsole = RunContentManager.getInstance(project).getSelectedContent().getExecutionConsole();
		if (executionConsole instanceof SMTRunnerConsoleView) {
			SMTRunnerConsoleView consoleView = (SMTRunnerConsoleView) executionConsole;

			SMTestProxy root = consoleView.getResultsViewer().getTestsRootNode();
			return root.getAllTests();
		}
		return Collections.emptyList();
	}

	void rerunLastUsedRunConfiguration(Project project) {
		RunnerAndConfigurationSettings settings = RunManager.getInstance(project).getSelectedConfiguration();

		if (settings != null) {
			ExecutionEnvironmentBuilder builder = ExecutionEnvironmentBuilder.createOrNull(DefaultRunExecutor.getRunExecutorInstance(), settings);

			if (builder != null) {
				ExecutionEnvironment environment = builder.build();
				ProgramRunnerUtil.executeConfiguration(environment, false, false);
			}
		}
	}

	@Override
	public void update(AnActionEvent e) {
		// Whether the action is available can depend on the current context.
		// For example, we can check if a project is open and a test run is active.
		Project project = e.getProject();
		boolean isAvailable = project != null && ExecutionManager.getInstance(project).getContentManager().getSelectedContent() != null;
		e.getPresentation().setEnabledAndVisible(isAvailable);
	}

}

