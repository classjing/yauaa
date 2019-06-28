/*
 * Yet Another UserAgent Analyzer
 * Copyright (C) 2013-2019 Niels Basjes
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nl.basjes.parse.useragent.analyze.treewalker.steps.value;

import nl.basjes.parse.useragent.analyze.treewalker.steps.Step;
import nl.basjes.parse.useragent.analyze.treewalker.steps.WalkList.WalkResult;
import nl.basjes.parse.useragent.parse.EvilManualUseragentStringHacks;
import nl.basjes.parse.useragent.parse.MatcherTree;
import org.antlr.v4.runtime.tree.ParseTree;

import static nl.basjes.parse.useragent.parse.EvilManualUseragentStringHacks.replaceString;

public class StepCleanVersion extends Step {

    @Override
    public WalkResult walk(ParseTree<MatcherTree> tree, String value) {
        String actualValue = getActualValue(tree, value);

        // Sanitize the provided value
        actualValue = replaceString(actualValue, "_", ".");
        actualValue = replaceString(actualValue, "/", " ");

        return walkNextStep(tree, actualValue);
    }

    @Override
    public boolean canFail(){
        return false;
    }

    @Override
    public String toString() {
        return "CleanVersion()";
    }
}
