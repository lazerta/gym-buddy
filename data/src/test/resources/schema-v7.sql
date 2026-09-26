-- Frozen v7 DDL reconstructed ONLY from main 466937a6f9ff44114164a31f24bf2a98ece186f2
-- RoomEvidenceRepositoryTest.v1Statements plus that commit's migrations 1 through 7.
-- Never regenerate this historical input from current Room entities.
CREATE TABLE IF NOT EXISTS `workout_sessions` (`sessionId` TEXT NOT NULL, `startedAtUs` INTEGER NOT NULL, PRIMARY KEY(`sessionId`));
CREATE TABLE IF NOT EXISTS `exercise_executions` (`executionId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `exerciseId` TEXT NOT NULL, `startedAtUs` INTEGER NOT NULL, PRIMARY KEY(`executionId`), FOREIGN KEY(`sessionId`) REFERENCES `workout_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE );
CREATE INDEX IF NOT EXISTS `index_exercise_executions_sessionId` ON `exercise_executions` (`sessionId`);
CREATE TABLE IF NOT EXISTS `sets` (`setId` TEXT NOT NULL, `executionId` TEXT NOT NULL, `setOrdinal` INTEGER NOT NULL, `startedAtUs` INTEGER NOT NULL, PRIMARY KEY(`setId`), FOREIGN KEY(`executionId`) REFERENCES `exercise_executions`(`executionId`) ON UPDATE NO ACTION ON DELETE CASCADE );
CREATE INDEX IF NOT EXISTS `index_sets_executionId` ON `sets` (`executionId`);
CREATE UNIQUE INDEX IF NOT EXISTS `index_sets_executionId_setOrdinal` ON `sets` (`executionId`, `setOrdinal`);
CREATE TABLE IF NOT EXISTS `analysis_contexts` (`setId` TEXT NOT NULL, `exerciseDefinitionId` TEXT NOT NULL, `exerciseDefinitionVersion` INTEGER NOT NULL, `exerciseDefinitionSemanticHash` TEXT NOT NULL, `exerciseProfileId` TEXT NOT NULL, `exerciseProfileVersion` INTEGER NOT NULL, `exerciseProfileSemanticHash` TEXT NOT NULL, `cameraProfileId` TEXT NOT NULL, `cameraProfileVersion` INTEGER NOT NULL, `cameraProfileSemanticHash` TEXT NOT NULL, `signalProfileId` TEXT NOT NULL, `signalProfileVersion` INTEGER NOT NULL, `signalProfileSemanticHash` TEXT NOT NULL, `primitiveProfileId` TEXT NOT NULL, `primitiveProfileVersion` INTEGER NOT NULL, `primitiveProfileSemanticHash` TEXT NOT NULL, `metricProfileId` TEXT NOT NULL, `metricProfileVersion` INTEGER NOT NULL, `metricProfileSemanticHash` TEXT NOT NULL, `formRuleSetId` TEXT NOT NULL, `formRuleSetVersion` INTEGER NOT NULL, `formRuleSetSemanticHash` TEXT NOT NULL, `cuePolicyId` TEXT NOT NULL, `cuePolicyVersion` INTEGER NOT NULL, `cuePolicySemanticHash` TEXT NOT NULL, `equipmentProfileId` TEXT, `equipmentProfileVersion` INTEGER, `equipmentProfileSemanticHash` TEXT, `calibrationProfileId` TEXT, `calibrationProfileVersion` INTEGER, `calibrationProfileSemanticHash` TEXT, PRIMARY KEY(`setId`), FOREIGN KEY(`setId`) REFERENCES `sets`(`setId`) ON UPDATE NO ACTION ON DELETE CASCADE );
CREATE TABLE IF NOT EXISTS `rep_evidence` (`repId` TEXT NOT NULL, `setId` TEXT NOT NULL, `repOrdinal` INTEGER NOT NULL, `stepId` TEXT NOT NULL, `primitive` TEXT NOT NULL, `startedAtUs` INTEGER NOT NULL, `completedAtUs` INTEGER NOT NULL, `classification` TEXT NOT NULL, PRIMARY KEY(`repId`), FOREIGN KEY(`setId`) REFERENCES `sets`(`setId`) ON UPDATE NO ACTION ON DELETE CASCADE );
CREATE INDEX IF NOT EXISTS `index_rep_evidence_setId` ON `rep_evidence` (`setId`);
CREATE UNIQUE INDEX IF NOT EXISTS `index_rep_evidence_setId_repOrdinal` ON `rep_evidence` (`setId`, `repOrdinal`);
CREATE TABLE IF NOT EXISTS `rep_signal_evidence` (`repId` TEXT NOT NULL, `signalId` TEXT NOT NULL, `unit` TEXT NOT NULL, `minValue` REAL, `maxValue` REAL, `meanValue` REAL, `lastValue` REAL, `confidence` REAL, PRIMARY KEY(`repId`, `signalId`), FOREIGN KEY(`repId`) REFERENCES `rep_evidence`(`repId`) ON UPDATE NO ACTION ON DELETE CASCADE );
CREATE INDEX IF NOT EXISTS `index_rep_signal_evidence_repId` ON `rep_signal_evidence` (`repId`);
CREATE TABLE IF NOT EXISTS `rep_metric_evidence` (`repId` TEXT NOT NULL, `metricId` TEXT NOT NULL, `unit` TEXT NOT NULL, `known` INTEGER NOT NULL, `value` REAL, `confidence` REAL, `unknownReason` TEXT, PRIMARY KEY(`repId`, `metricId`), FOREIGN KEY(`repId`) REFERENCES `rep_evidence`(`repId`) ON UPDATE NO ACTION ON DELETE CASCADE );
CREATE INDEX IF NOT EXISTS `index_rep_metric_evidence_repId` ON `rep_metric_evidence` (`repId`);
CREATE TABLE IF NOT EXISTS `form_observations` (`observationId` TEXT NOT NULL, `setId` TEXT NOT NULL, `repId` TEXT NOT NULL, `ruleId` TEXT NOT NULL, `ruleVersion` INTEGER NOT NULL, `state` TEXT NOT NULL, `severity` TEXT NOT NULL, `confidence` REAL, `evidenceValue` REAL, PRIMARY KEY(`observationId`), FOREIGN KEY(`repId`) REFERENCES `rep_evidence`(`repId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`setId`) REFERENCES `sets`(`setId`) ON UPDATE NO ACTION ON DELETE CASCADE );
CREATE INDEX IF NOT EXISTS `index_form_observations_repId` ON `form_observations` (`repId`);
CREATE INDEX IF NOT EXISTS `index_form_observations_setId` ON `form_observations` (`setId`);
CREATE TABLE IF NOT EXISTS `cue_events` (`cueId` TEXT NOT NULL, `setId` TEXT NOT NULL, `repId` TEXT NOT NULL, `observationId` TEXT, `ruleId` TEXT NOT NULL, `emittedAtUs` INTEGER NOT NULL, `severity` TEXT NOT NULL, PRIMARY KEY(`cueId`), FOREIGN KEY(`setId`) REFERENCES `sets`(`setId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`repId`) REFERENCES `rep_evidence`(`repId`) ON UPDATE NO ACTION ON DELETE CASCADE );
CREATE INDEX IF NOT EXISTS `index_cue_events_setId` ON `cue_events` (`setId`);
CREATE INDEX IF NOT EXISTS `index_cue_events_repId` ON `cue_events` (`repId`);
CREATE TABLE IF NOT EXISTS `cue_responses` (`responseId` TEXT NOT NULL, `setId` TEXT NOT NULL, `cueId` TEXT NOT NULL, `repId` TEXT NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`responseId`), FOREIGN KEY(`cueId`) REFERENCES `cue_events`(`cueId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`repId`) REFERENCES `rep_evidence`(`repId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`setId`) REFERENCES `sets`(`setId`) ON UPDATE NO ACTION ON DELETE CASCADE );
CREATE INDEX IF NOT EXISTS `index_cue_responses_cueId` ON `cue_responses` (`cueId`);
CREATE INDEX IF NOT EXISTS `index_cue_responses_repId` ON `cue_responses` (`repId`);
CREATE INDEX IF NOT EXISTS `index_cue_responses_setId` ON `cue_responses` (`setId`);
CREATE TABLE IF NOT EXISTS `tracking_quality_summaries` (`setId` TEXT NOT NULL, `observableFrames` INTEGER NOT NULL, `degradedFrames` INTEGER NOT NULL, `pausedFrames` INTEGER NOT NULL, `unknownFrames` INTEGER NOT NULL, PRIMARY KEY(`setId`), FOREIGN KEY(`setId`) REFERENCES `sets`(`setId`) ON UPDATE NO ACTION ON DELETE CASCADE );
CREATE TABLE IF NOT EXISTS `set_summaries` (`setId` TEXT NOT NULL, `endedAtUs` INTEGER NOT NULL, `completedReps` INTEGER NOT NULL, `assistedReps` INTEGER NOT NULL, `uncertainReps` INTEGER NOT NULL, PRIMARY KEY(`setId`), FOREIGN KEY(`setId`) REFERENCES `sets`(`setId`) ON UPDATE NO ACTION ON DELETE CASCADE );
CREATE TABLE IF NOT EXISTS `cue_deliveries` (
    `cueId` TEXT NOT NULL,
    `state` TEXT NOT NULL,
    PRIMARY KEY(`cueId`),
    FOREIGN KEY(`cueId`) REFERENCES `cue_events`(`cueId`) ON UPDATE NO ACTION ON DELETE CASCADE
);
ALTER TABLE `sets` ADD COLUMN `actualLoadValue` REAL;
ALTER TABLE `sets` ADD COLUMN `actualLoadUnit` TEXT;
CREATE TABLE IF NOT EXISTS `workout_flow_states` (
    `checkpointId` TEXT NOT NULL,
    `completedSetId` TEXT NOT NULL,
    `focus` TEXT NOT NULL,
    `plannedNextLoadValue` REAL,
    `plannedNextLoadUnit` TEXT,
    `restStartedAtEpochMs` INTEGER NOT NULL,
    PRIMARY KEY(`checkpointId`),
    FOREIGN KEY(`completedSetId`) REFERENCES `sets`(`setId`) ON UPDATE NO ACTION ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS `index_workout_flow_states_completedSetId` ON `workout_flow_states` (`completedSetId`);
CREATE TABLE IF NOT EXISTS `personal_calibration_profiles` (
    `slotId` TEXT NOT NULL,
    `calibrationProfileId` TEXT NOT NULL,
    `profileVersion` INTEGER NOT NULL,
    `semanticHash` TEXT NOT NULL,
    `payload` TEXT NOT NULL,
    PRIMARY KEY(`slotId`)
);
ALTER TABLE `workout_flow_states` ADD COLUMN `state` TEXT NOT NULL DEFAULT 'REST';
CREATE TABLE IF NOT EXISTS `interrupted_sets` (
    `setId` TEXT NOT NULL,
    `recoveredAtEpochMs` INTEGER NOT NULL,
    `committedReps` INTEGER NOT NULL,
    PRIMARY KEY(`setId`),
    FOREIGN KEY(`setId`) REFERENCES `sets`(`setId`) ON UPDATE NO ACTION ON DELETE CASCADE
);
ALTER TABLE `workout_sessions` ADD COLUMN `startedAtEpochMs` INTEGER NOT NULL DEFAULT 0;
ALTER TABLE `exercise_executions` ADD COLUMN `startedAtEpochMs` INTEGER NOT NULL DEFAULT 0;
ALTER TABLE `sets` ADD COLUMN `startedAtEpochMs` INTEGER NOT NULL DEFAULT 0;
ALTER TABLE `set_summaries` ADD COLUMN `endedAtEpochMs` INTEGER NOT NULL DEFAULT 0;
ALTER TABLE `tracking_quality_summaries` ADD COLUMN `activeObservableFrames` INTEGER NOT NULL DEFAULT 0;
ALTER TABLE `tracking_quality_summaries` ADD COLUMN `activeDegradedFrames` INTEGER NOT NULL DEFAULT 0;
ALTER TABLE `tracking_quality_summaries` ADD COLUMN `activePausedFrames` INTEGER NOT NULL DEFAULT 0;
ALTER TABLE `tracking_quality_summaries` ADD COLUMN `activeUnknownFrames` INTEGER NOT NULL DEFAULT 0;
ALTER TABLE `tracking_quality_summaries` ADD COLUMN `interruptionEpisodes` INTEGER NOT NULL DEFAULT 0;
ALTER TABLE `tracking_quality_summaries` ADD COLUMN `cameraDisturbanceEpisodes` INTEGER NOT NULL DEFAULT 0;
ALTER TABLE `tracking_quality_summaries` ADD COLUMN `observedViewClass` TEXT;
ALTER TABLE `tracking_quality_summaries` ADD COLUMN `activeFrameFillMean` REAL;
CREATE TABLE IF NOT EXISTS `personal_calibration_profile_history` (
    `calibrationProfileId` TEXT NOT NULL,
    `profileVersion` INTEGER NOT NULL,
    `semanticHash` TEXT NOT NULL,
    `payload` TEXT NOT NULL,
    PRIMARY KEY(`calibrationProfileId`, `profileVersion`)
);
INSERT OR IGNORE INTO `personal_calibration_profile_history`
    (`calibrationProfileId`, `profileVersion`, `semanticHash`, `payload`)
SELECT `calibrationProfileId`, `profileVersion`, `semanticHash`, `payload`
FROM `personal_calibration_profiles`;
