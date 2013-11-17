/***************************************************************************
* Copyright (c) 2013 VMware, Inc. All Rights Reserved.
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
***************************************************************************/

package com.vmware.vhadoop.util;

import org.junit.Ignore;
import org.junit.internal.AssumptionViolatedException;
import org.junit.internal.runners.model.EachTestNotifier;
import org.junit.runner.Description;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

public class RepeatTestRunner extends BlockJUnit4ClassRunner
{
   public RepeatTestRunner(Class<?> klass) throws InitializationError {
      super(klass);
   }

   @Override
   protected Description describeChild(FrameworkMethod method) {
      if (method.getAnnotation(RepeatTest.class) != null && method.getAnnotation(Ignore.class) == null) {
         return describeRepeatTest(method);
      }
      return super.describeChild(method);
   }

   private Description describeRepeatTest(FrameworkMethod method) {
      int times = method.getAnnotation(RepeatTest.class).value();

      Description description = Description.createSuiteDescription(testName(method) + " [" + times + " times]", method.getAnnotations());

      for (int i = 1; i <= times; i++) {
         description.addChild(Description.createTestDescription(getTestClass().getJavaClass(), "[" + i + "] " + testName(method)));
      }
      return description;
   }

   @Override
   protected void runChild(final FrameworkMethod method, RunNotifier notifier) {
      Description description = describeChild(method);

      if (method.getAnnotation(RepeatTest.class) != null && method.getAnnotation(Ignore.class) == null) {
         runRepeatedly(methodBlock(method), description, notifier);
         return;
      }

      super.runChild(method, notifier);
   }

   private void runRepeatedly(Statement statement, Description description, RunNotifier notifier) {
      for (Description desc : description.getChildren()) {
         EachTestNotifier eachNotifier = new EachTestNotifier(notifier, desc);
         eachNotifier.fireTestStarted();
         try {
            statement.evaluate();
         } catch (AssumptionViolatedException e) {
            eachNotifier.addFailedAssumption(e);
//            return;
         } catch (Throwable e) {
            eachNotifier.addFailure(e);
//            return;
         } finally {
            eachNotifier.fireTestFinished();
         }
      }
   }

}