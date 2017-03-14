/*
 *    ALCrossValidationTask.java
 *    Copyright (C) 2017 Otto-von-Guericke-University, Magdeburg, Germany
 *    @author Cornelius Styp von Rekowski (cornelius.styp@ovgu.de)
 *
 *    This program is free software; you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation; either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program. If not, see <http://www.gnu.org/licenses/>.
 *    
 */
package moa.tasks.active;

import java.util.ArrayList;
import java.util.List;

import com.github.javacliparser.FloatOption;
import com.github.javacliparser.IntOption;
import com.github.javacliparser.ListOption;
import com.github.javacliparser.Option;
import com.github.javacliparser.Options;

import moa.classifiers.active.ALClassifier;
import moa.core.ObjectRepository;
import moa.evaluation.ALClassificationPerformanceEvaluator;
import moa.evaluation.PreviewCollection;
import moa.evaluation.PreviewCollectionLearningCurveWrapper;
import moa.options.ClassOption;
import moa.options.ClassOptionWithListenerOption;
import moa.options.EditableMultiChoiceOption;
import moa.streams.ExampleStream;
import moa.streams.KFoldStream;
import moa.tasks.TaskMonitor;
import moa.tasks.active.ALMultiBudgetTask.RefreshParamsChangeListener;

/**
 * This task extensively evaluates an active learning classifier on a stream.
 * First, the given data set is partitioned into separate folds for performing
 * cross validation. On each fold, the ALMultiBudgetTask is performed which
 * individually evaluates the active learning classifier for each element of a
 * set of budgets. The individual evaluation is done by prequential evaluation
 * (testing, then training with each example in sequence).
 * 
 * @author Cornelius Styp von Rekowski (cornelius.styp@ovgu.de)
 * @version $Revision: 1 $
 */
public class ALCrossValidationTask extends ALMainTask {

	private static final long serialVersionUID = 1L;

	@Override
	public String getPurposeString() {
		return "Evaluates an active learning classifier on a stream by"
				+ " performing cross validation and on each fold evaluating"
				+ " the classifier for each element of a set of budgets using"
				+ " prequential evaluation (testing, then training with each" 
				+ " example in  sequence).";
	}

	/* options actually used in ALPrequentialEvaluationTask */
	public ClassOptionWithListenerOption learnerOption = 
			new ClassOptionWithListenerOption(
				"learner", 'l', "Learner to train.", ALClassifier.class, 
	            "moa.classifiers.active.ALZliobaite2011");

	public ClassOption streamOption = new ClassOption(
			"stream", 's', "Stream to learn from.", ExampleStream.class,
			"generators.RandomTreeGenerator");

	public ClassOption prequentialEvaluatorOption = new ClassOption(
			"prequentialEvaluator", 'e',
			"Prequential classification performance evaluation method.", 
			ALClassificationPerformanceEvaluator.class,
			"ALBasicClassificationPerformanceEvaluator");

	public IntOption instanceLimitOption = new IntOption("instanceLimit", 'i',
			"Maximum number of instances to test/train on  (-1 = no limit).", 
			100000000, -1, Integer.MAX_VALUE);
	
	public IntOption timeLimitOption = new IntOption("timeLimit", 't',
            "Maximum number of seconds to test/train for (-1 = no limit).", -1,
            -1, Integer.MAX_VALUE);

	/* options actually used in ALMultiBudgetTask */
	public EditableMultiChoiceOption budgetParamNameOption = 
			new EditableMultiChoiceOption(
					"budgetParamName", 'p', 
					"Name of the parameter to be used as budget.",
					new String[]{"budget"}, 
					new String[]{"default budget parameter name"}, 
					0);
	
	public ListOption budgetsOption = new ListOption("budgets", 'b',
			"List of budgets to train classifiers for.",
			new FloatOption("budget", ' ', "Active learner budget.", 0.9), 
			new FloatOption[]{
					new FloatOption("", ' ', "", 0.5),
					new FloatOption("", ' ', "", 0.9)
			}, ',');
	
	public ClassOption multiBudgetEvaluatorOption = new ClassOption(
			"multiBudgetEvaluator", 'm',
            "Multi-budget classification performance evaluation method.",
            ALClassificationPerformanceEvaluator.class,
            "ALBasicClassificationPerformanceEvaluator");
	
	/* options used in in this class */
	public IntOption numFoldsOption = new IntOption("numFolds", 'k', 
			"Number of cross validation folds.", 10);

	public ClassOption crossValEvaluatorOption = new ClassOption(
			"crossValidationEvaluator", 'c',
			"Cross validation evaluation method.", 
			ALClassificationPerformanceEvaluator.class,
			"ALBasicClassificationPerformanceEvaluator");

	/*
	 * Possible extensions/further options:
	 * - Ensembles of learners (ensemble size)
	 * - Sample frequency
	 * - Memory check frequency
	 * - Dump file
	 */

	private ArrayList<ALTaskThread> subtaskThreads = new ArrayList<>();
	private ArrayList<ALTaskThread> flattenedSubtaskThreads = new ArrayList<>();
	
	
	public ALCrossValidationTask() {
		super();
		
		// reset last learner option
		ALMultiBudgetTask.lastLearnerOption = null;
		
		// Enable refreshing the budgetParamNameOption depending on the
		// learnerOption
		this.learnerOption.setListener(new RefreshParamsChangeListener(
				this.learnerOption, this.budgetParamNameOption));
	}
	
	@Override
	public Options getOptions() {
		Options options = super.getOptions();
		
		// Get the initial values for the budgetParamNameOption
		ALMultiBudgetTask.refreshBudgetParamNameOption(
				this.learnerOption, this.budgetParamNameOption);
		
		return options;
	}
	
	@Override
	protected void prepareForUseImpl(TaskMonitor monitor, ObjectRepository repository) {
		super.prepareForUseImpl(monitor, repository);

		// setup subtask for each cross validation fold
		for (int i = 0; i < this.numFoldsOption.getValue(); i++) {

			// wrap base stream into a KFoldStream to split up data
			KFoldStream stream = new KFoldStream();

			for (Option opt : stream.getOptions().getOptionArray()) {
				switch (opt.getName()) {
				case "stream":
					opt.setValueViaCLIString(this.streamOption.getValueAsCLIString());
					break;
				case "foldIndex":
					opt.setValueViaCLIString(String.valueOf(i));
					break;
				case "numFolds":
					opt.setValueViaCLIString(this.numFoldsOption.getValueAsCLIString());
					break;
				}
			}

			// create subtask
			ALMultiBudgetTask foldTask = new ALMultiBudgetTask();
			foldTask.setIsLastSubtaskOnLevel(
					this.isLastSubtaskOnLevel, i == this.numFoldsOption.getValue() - 1);

			for (Option opt : foldTask.getOptions().getOptionArray()) {
				switch (opt.getName()) {
				case "learner":
					opt.setValueViaCLIString(this.learnerOption.getValueAsCLIString());
					break;
				case "stream":
					opt.setValueViaCLIString(
							ClassOption.objectToCLIString(stream, ExampleStream.class));
					break;
				case "prequential evaluator":
					opt.setValueViaCLIString(
							this.prequentialEvaluatorOption.getValueAsCLIString());
					break;
				case "budgetParamName":
					opt.setValueViaCLIString(
							this.budgetParamNameOption.getValueAsCLIString());
					break;
				case "budgets":
					opt.setValueViaCLIString(
							this.budgetsOption.getValueAsCLIString());
					break;
				case "multi-budget evaluator":
					opt.setValueViaCLIString(
							this.multiBudgetEvaluatorOption.getValueAsCLIString());
					break;
				case "instanceLimit":
					opt.setValueViaCLIString(
							this.instanceLimitOption.getValueAsCLIString());
					break;
				case "timeLimit":
					opt.setValueViaCLIString(
							this.timeLimitOption.getValueAsCLIString());
					break;
				}
			}

			foldTask.prepareForUse();

			List<ALTaskThread> childSubtasks = foldTask.getSubtaskThreads();

			ALTaskThread subtaskThread = new ALTaskThread(foldTask);
			this.subtaskThreads.add(subtaskThread);

			this.flattenedSubtaskThreads.add(subtaskThread);
			this.flattenedSubtaskThreads.addAll(childSubtasks);
		}

	}

	@Override
	public Class<?> getTaskResultType() {
		return PreviewCollection.class;
	}

	@Override
	protected Object doMainTask(
			TaskMonitor monitor, ObjectRepository repository) 
	{
		// initialize the learning curve collection
		PreviewCollection<PreviewCollection<PreviewCollectionLearningCurveWrapper>> previewCollection = new PreviewCollection<>("cross validation entry id", "fold id", this.getClass());
		

		monitor.setCurrentActivity("Performing cross validation...", 50.0);
		
		// start subtasks
		monitor.setCurrentActivity("Performing cross validation...", -1.0);
		for(int i = 0; i < this.subtaskThreads.size(); ++i)
		{
			subtaskThreads.get(i).start();
		}


		// get the number of subtask threads
		int numSubtaskThreads = subtaskThreads.size();
		// check the previews of subtaskthreads
		boolean allThreadsCompleted = false;
		// iterate while there are threads active
		while(!allThreadsCompleted)
		{
			allThreadsCompleted = true;
			int oldNumEntries = previewCollection.numEntries();
			double completionSum = 0;
			// iterate over all threads
			for(int i = 0; i < numSubtaskThreads; ++i)
			{
				ALTaskThread currentTaskThread = subtaskThreads.get(i);
				// check if the thread is completed
				allThreadsCompleted &= currentTaskThread.isComplete();
				// get the completion fraction
				completionSum += currentTaskThread.getCurrentActivityFracComplete();
				// get the latest preview
				@SuppressWarnings("unchecked")
				PreviewCollection<PreviewCollectionLearningCurveWrapper> latestPreview = (PreviewCollection<PreviewCollectionLearningCurveWrapper>)currentTaskThread.getLatestResultPreview();
				// ignore the preview if it is null
				if(latestPreview != null && latestPreview.numEntries() > 0)
				{	
					// update/add the learning curve to the learning curve collection
					previewCollection.setPreview(i, latestPreview);
				}
				else
				{
					// skip for loop until all threads before were at least added once
					break;
				}
			}
			
			double completionFraction = completionSum / numSubtaskThreads;
			
			monitor.setCurrentActivityFractionComplete(completionFraction);
			
			// check if the task should abort or paused
    		if (monitor.taskShouldAbort()) {
                return null;
            }
			
			// check if the preview has actually changed
			if(oldNumEntries < previewCollection.numEntries())
			{
				// check if a preview is requested
	    		if (monitor.resultPreviewRequested() || isSubtask()) {
	    			// send the latest preview to the monitor
	                monitor.setLatestResultPreview(previewCollection.copy());
	            }
			}
		}
		
		return previewCollection;
	}

	@Override
	public List<ALTaskThread> getSubtaskThreads() {
		return this.flattenedSubtaskThreads;
	}
}
