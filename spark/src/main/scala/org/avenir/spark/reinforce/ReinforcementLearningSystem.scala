/*
 * avenir-spark: Predictive analytic based on Spark
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.avenir.spark.reinforce

import org.chombo.spark.common.JobConfiguration
import org.apache.spark.SparkContext
import com.typesafe.config.Config
import org.chombo.util.BasicUtils
import org.avenir.reinforce.MultiArmBanditLearnerFactory
import org.avenir.reinforce.MultiArmBanditLearner

object ReinforcementLearningSystem extends JobConfiguration {
   /**
    * @param args
    * @return
    */
   def main(args: Array[String]) {
	   val appName = "reinforcementLearningSystem"
	   val Array(inputPath: String, outputPath: String, configFile: String) = getCommandLineArgs(args, 3)
	   val config = createConfig(configFile)
	   val sparkConf = createSparkConf(appName, config, false)
	   val sparkCntxt = new SparkContext(sparkConf)
	   val appConfig = config.getConfig(appName)
	   
	   //configurations
	   val fieldDelimIn = getStringParamOrElse(appConfig, "field.delim.in", ",")
	   val fieldDelimOut = getStringParamOrElse(appConfig, "field.delim.out", ",")
	   
	   val actions = BasicUtils.fromListToStringArray(getMandatoryStringListParam(appConfig, "action.list"))
	   val batchSize = getMandatoryIntParam(appConfig, "decision.batch.size")
	   val rewardFeedbackFilePath = getOptionalStringParam(appConfig, "reward.feedback.file.path")
	   val modelStateOutputFilePath = getMandatoryStringParam(appConfig, "model.state.output.file.path")
	   val debugOn = getBooleanParamOrElse(appConfig, "debug.on", false)
	   val saveOutput = getBooleanParamOrElse(appConfig, "save.output", true)

	   
	   //algorithm and algorithm specific configuration
	   val learnAlgo = getMandatoryStringParam(appConfig, "learning.algorithm")
	   val appAlgoConfig = appConfig.getConfig(learnAlgo)
	   val configParams = getConfig(learnAlgo, appAlgoConfig)
	   
	   //model state file
	   val data = sparkCntxt.textFile(inputPath)
	   
	   //key by group
	   val keyedData = data.map(line => {
	     val items = BasicUtils.splitOnFirstOccurence(line, fieldDelimIn, true)
	     (items(0), items(1))
	   })
	   
	   //add state record to learner
	   val addToLeaner = (learner:MultiArmBanditLearner, state:String) => {
	     learner.buildModel(state)
	     learner
	   }
	   
	   //create learner
	   val createLearner = (state:String) => {
	     val learner = MultiArmBanditLearnerFactory.create(learnAlgo, actions, configParams)
	     addToLeaner(learner, state)
	   }
	   
	   //merge learners
	   val mergeLearners = (learnerOne : MultiArmBanditLearner, learnerTwo:MultiArmBanditLearner) => {
	     learnerOne.merge(learnerTwo)
	     learnerOne
	   }

	   //build group wise learners from state
	   var groupWiseLearners =  keyedData.combineByKey(createLearner, addToLeaner, mergeLearners)

	   //optionally add reward to learners
	   groupWiseLearners = rewardFeedbackFilePath match {
	     case Some(path:String) => {
	       val rewardData = sparkCntxt.textFile(path)
	       val keyedReward = rewardData.map(line => {
	         val items = BasicUtils.splitOnFirstOccurence(line, fieldDelimIn, true)
	        (items(0), items(1))
	       })
	       
	       //update learner with reward
	       val coGroupedLearnerRewards = groupWiseLearners.cogroup(keyedReward)
	       val updatedLearners = coGroupedLearnerRewards.mapValues(v => {
	         val learner = v._1.head
	         val reawrds = v._2
	         reawrds.foreach(reward => {
	           val items = reward.split(fieldDelimIn)
	           learner.setReward(items(0), items(1).toDouble)
	         })
	         learner
	       })
	       updatedLearners
	     } 
	     
	     case None => groupWiseLearners
	   }
	   
	   //generate actions
	   val batch = for (i <- 1 to batchSize) yield i
	   val groupActions = groupWiseLearners.flatMapValues(learner => {
	     val actions = batch.map(b => {learner.nextAction().getId()})
	     actions
	   })
	    
	   if (debugOn) {
		   val actionArray = groupActions.collect
	       actionArray.foreach(a => {
	         println("group:" + a._1)
	         println("action:" + a._2)
	       })
	   }
	  
	   //save recommended actions
	   if (saveOutput) {
	     groupActions.saveAsTextFile(outputPath)
	   }
	   
	   //save state
	   rewardFeedbackFilePath match {
	     case Some(path:String) => {
	       //only if model state was updated
	       val modelState = groupWiseLearners.flatMapValues(v => {
	         val state = v.getModel()
	         state
	       }).map(kv => {
	         kv._1 + kv._2
	       })
	       modelState.saveAsTextFile(modelStateOutputFilePath)
	     }
	     case None =>
	   }
   }
   
   /**
   * @param args
   * @return
   */
   def getConfig(learnAlgo : String, appAlgoConfig : Config) : java.util.Map[String, Object] = {
	   val configParams = new java.util.HashMap[String, Object]()
	   learnAlgo match {
	       case MultiArmBanditLearnerFactory.RANDOM_GREEDY => {
	         configParams.put("current.decision.round", new Integer(appAlgoConfig.getInt("current.decision.round")))
	         configParams.put("random.selection.prob", new java.lang.Double(appAlgoConfig.getDouble("random.selection.prob")))
	         configParams.put("prob.reduction.algorithm", appAlgoConfig.getString("prob.reduction.algorithm"))
	         configParams.put("prob.reduction.constant", new java.lang.Double(appAlgoConfig.getDouble("prob.reduction.constant")))
	         configParams.put("auer.greedy.constant", new Integer(appAlgoConfig.getInt("auer.greedy.constant")))
	         configParams.put("decision.batch.size", new Integer(appAlgoConfig.getInt("decision.batch.size")))
	       }
	       case MultiArmBanditLearnerFactory.UPPER_CONFIDENCE_BOUND_ONE => {
	       }
	       case _ => throw new IllegalStateException("invalid RL algorithm")
	   }
	     
	   configParams
	}
  
}