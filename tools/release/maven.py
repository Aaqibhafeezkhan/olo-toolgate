# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Publish prepared Maven artifacts through Central or a standard Maven repository."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.release import central, development


def publish(assets, version, pom_path=None, project=None, run=None):
    development.validate_maven_repository()
    if not re.fullmatch(r'[0-9A-Za-z][0-9A-Za-z_.-]*', version):
        raise ValueError('Unsafe Maven version')
    assets = Path(assets)
    pom_path = Path(pom_path) if pom_path else assets/f'toolgate-contracts-{version}.pom'
    pom = ET.parse(pom_path).getroot()
    ns = {'m':'http://maven.apache.org/POM/4.0.0'}
    if [pom.findtext('m:'+key, namespaces=ns) for key in ('groupId','artifactId','version')] != ['io.github.olo-labs','toolgate-contracts',version]:
        raise ValueError('Maven POM identity mismatch')
    if os.environ['MAVEN_REPOSITORY_URL'] == central.UPLOAD_URL:
        central.publish(assets, version, pom_path)
        return
    for classifier in ('','-sources','-javadoc'):
        if not (assets/f'toolgate-contracts-{version}{classifier}.jar').is_file():
            raise ValueError('Missing prepared Maven artifact: '+classifier)
    module = assets/f'toolgate-contracts-{version}.module'
    if module.exists():
        identity = json.loads(module.read_text(encoding='utf-8')).get('component',{})
        if [identity.get(key) for key in ('group','module','version')] != ['io.github.olo-labs','toolgate-contracts',version]:
            raise ValueError('Gradle module identity mismatch')
    project = Path(project) if project else assets.parent/'maven-publication'
    project.mkdir(parents=True, exist_ok=True)
    staged = project/'assets'
    staged.mkdir(exist_ok=True)
    for classifier in ('','-sources','-javadoc'):
        name = f'toolgate-contracts-{version}{classifier}.jar'
        shutil.copyfile(assets/name, staged/name)
    shutil.copyfile(pom_path, staged/pom_path.name if pom_path.name == f'toolgate-contracts-{version}.pom' else f'toolgate-contracts-{version}.pom')
    module_line = ''
    if module.exists():
        shutil.copyfile(module, staged/module.name)
        module_line = 'artifact(assets.resolve("toolgate-contracts-${project.version}.module")) { extension = "module" }'
    (project/'settings.gradle.kts').write_text('rootProject.name = "toolgate-prepared-publication"\n', encoding='utf-8')
    script = '''// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
plugins { `maven-publish` }
group = "io.github.olo-labs"
version = VERSION_VALUE
val assets = file("assets")
publishing {
    publications {
        create<MavenPublication>("verified") {
            artifactId = "toolgate-contracts"
            artifact(assets.resolve("toolgate-contracts-${project.version}.jar"))
            artifact(assets.resolve("toolgate-contracts-${project.version}-sources.jar")) { classifier = "sources" }
            artifact(assets.resolve("toolgate-contracts-${project.version}-javadoc.jar")) { classifier = "javadoc" }
            MODULE_LINE
        }
    }
    repositories {
        maven {
            name = "remote"
            url = uri(providers.environmentVariable("MAVEN_REPOSITORY_URL").get())
            credentials {
                username = providers.environmentVariable("MAVEN_USERNAME").orNull
                password = providers.environmentVariable("MAVEN_PASSWORD").orNull
            }
        }
    }
}
tasks.withType<GenerateMavenPom>().configureEach {
    doLast { destination.writeBytes(assets.resolve("toolgate-contracts-${project.version}.pom").readBytes()) }
}
'''.replace('VERSION_VALUE', json.dumps(version)).replace('MODULE_LINE', module_line)
    (project/'build.gradle.kts').write_text(script, encoding='utf-8')
    wrapper = ROOT/('gradlew.bat' if os.name == 'nt' else 'gradlew')
    (run or development.run)(str(wrapper), '--no-daemon', '--project-dir', str(project), 'publishVerifiedPublicationToRemoteRepository')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--pom', type=Path)
    args = parser.parse_args()
    publish(args.assets, args.version, args.pom)
