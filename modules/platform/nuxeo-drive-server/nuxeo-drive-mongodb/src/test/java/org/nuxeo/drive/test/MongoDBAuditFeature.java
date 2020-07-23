/*
 * (C) Copyright 2020 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.nuxeo.drive.test;

import org.nuxeo.drive.mongodb.TestMongoDBAuditChangeFinder;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.platform.audit.AuditFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.RunnerFeature;

/**
 * MongoDB audit feature for nuxeo drive. This Feature is necessary for the deployment annotations to be taken into
 * account by {@link TestMongoDBAuditChangeFinder}.
 *
 * @since 11.2
 */
@Features({ AutomationFeature.class, AuditFeature.class, org.nuxeo.mongodb.audit.MongoDBAuditFeature.class })
@Deploy("org.nuxeo.drive.mongodb")
public class MongoDBAuditFeature implements RunnerFeature {

}
