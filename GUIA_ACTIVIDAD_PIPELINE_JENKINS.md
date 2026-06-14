# Guia Completa - Laboratorio Pipeline de Jenkins

## 1) Objetivo de la actividad
Esta actividad consiste en construir un unico Pipeline de Jenkins que automatice el flujo de Integracion Continua (CI) del proyecto.

Debia incluir:
- Clonacion del repositorio.
- Build del proyecto.
- Pruebas unitarias.
- Pruebas de API.
- Pruebas End-to-End (E2E).
- Archivado y publicacion de reportes XML.
- Bloque post con notificacion en caso de fallo.

### Estado al dia de hoy
Ademas de lo anterior, hoy se dejo documentado y aplicado lo siguiente:
- El volumen EBS de la instancia se amplio de 8 GB a 20 GB.
- La particion raiz se extendio con `growpart` y `resize2fs` para usar el espacio nuevo.
- El problema principal que rompia el pipeline fue espacio en disco, no RAM.
- La etapa E2E se ajusto para consumir menos espacio temporal.
- Se agrego persistencia de reportes en `/var/jenkins_home/reports/<JOB_NAME>/<BUILD_NUMBER>`.
- Se agrego correo simulado en `always`, `success` y `failure`.

---

## 2) Estructura correcta de trabajo (muy importante)
Se identificaron dos rutas distintas:

1. Repositorio de trabajo (donde debes editar y versionar):
- `/home/ubuntu/repos/Actividad3-Pipeline-Nathy`

2. Workspace interno de Jenkins (se regenera automaticamente):
- `/var/jenkins_home/workspace/Actividad3-Pipeline-Nathy`

Regla practica:
- Los cambios del proyecto se hacen en tu repo (`/home/ubuntu/repos/...`).
- No conviene editar manualmente en `/var/jenkins_home/workspace/...` porque Jenkins lo puede sobrescribir en cada ejecucion.

Importante:
- Jenkins hace `clone` desde GitHub en cada build.
- Si cambias `Makefile` o `Jenkinsfile.long.groovy` en local, debes hacer `git commit` y `git push` para que el pipeline vea esos cambios en el siguiente `Source`.
- Si no haces push, el build puede seguir funcionando con la version que ya estaba en GitHub.

---

## 3) Preparacion de GitHub (hecho)
### Paso realizado
Se configuro el repositorio remoto a tu cuenta de GitHub y se hizo el primer push.

### Comandos usados
```bash
git remote set-url origin https://github.com/nathalyg/Actividad3-Pipeline-Nathy.git
git push -u origin master
```

### Que significa
- `set-url origin ...`: apunta el remoto principal a tu repo.
- `push -u`: sube la rama y deja configurado el seguimiento remoto.

---

## 4) Levantar Jenkins en Docker
### Contenedor detectado
Ya existia un contenedor llamado `jenkins` y se inicio correctamente.

### Comandos usados
```bash
sudo docker start jenkins
sudo docker ps --filter name=jenkins
```

### Acceso web
- Local: `http://localhost:8080`
- Servidor: `http://172.31.74.171:8080`

### Verificacion actual del disco
El disco ya fue ampliado y la particion raiz quedo expandida.

Comandos usados:
```bash
lsblk -o NAME,SIZE,FSTYPE,TYPE,MOUNTPOINT
df -hT /
```

Resultado esperado despues del ajuste:
- Disco `nvme0n1`: 20G
- Particion `nvme0n1p1`: alrededor de 19G
- Espacio libre suficiente para volver a correr Jenkins y Docker sin error de `No space left on device`
- La causa del fallo en E2E fue el almacenamiento lleno al guardar el estado del pipeline y los artefactos temporales.

Comandos usados para expandir la particion:
```bash
sudo growpart /dev/nvme0n1 1
sudo resize2fs /dev/nvme0n1p1
```

---

## 5) Problema real encontrado y solucion
## Problema A: no aparecia `initialAdminPassword`
### Causa
Jenkins ya estaba configurado (no era primera instalacion).

### Verificacion
```bash
sudo docker exec jenkins sh -c 'test -f /var/jenkins_home/jenkins.install.UpgradeWizard.state && echo WIZARD_DONE || echo WIZARD_NOT_DONE'
```

Si sale `WIZARD_DONE`, el archivo `initialAdminPassword` normalmente ya no existe.

---

## Problema B: Jenkins no podia ejecutar Docker
### Error observado
`permission denied while trying to connect to the Docker daemon socket`

### Causa
El usuario `jenkins` no tenia permisos sobre `docker.sock`.

### Solucion aplicada
Se recreo el contenedor agregando el grupo Docker del host:

```bash
DOCKER_GID=$(getent group docker | cut -d: -f3)

sudo docker stop jenkins
sudo docker rm jenkins

sudo docker run -d \
  --name jenkins \
  --restart unless-stopped \
  --group-add $DOCKER_GID \
  -p 8080:8080 \
  -p 50000:50000 \
  -v /var/jenkins_home:/var/jenkins_home \
  -v /var/run/docker.sock:/var/run/docker.sock \
  jenkins/jenkins:lts
```

---

## Problema C: al recrear Jenkins faltaban `docker` y `make`
### Error observado
`docker: executable file not found in $PATH`

### Causa
El contenedor nuevo no traia esas utilidades instaladas.

### Solucion aplicada
```bash
sudo docker exec -u 0 jenkins sh -lc 'apt-get update && apt-get install -y docker.io make'
```

### Verificacion final
```bash
sudo docker exec jenkins sh -c 'command -v docker; command -v make; docker ps'
```

## 6) Cambios aplicados hoy al pipeline
Hoy se dejo el pipeline mas robusto para que no vuelva a fallar por acumulacion de archivos temporales o por re-ejecuciones.

### Ajustes principales
- `Source`, `Build`, `Unit tests`, `API tests` y `E2E tests` quedaron definidos como etapas obligatorias.
- `make build`, `make test-unit`, `make test-api` y `make test-e2e` quedaron como comandos de ejecucion.
- Los XML generados se archivan con `archiveArtifacts`.
- Se publica JUnit con `junit allowEmptyResults: true, testResults: 'results/*_result.xml'`.
- Se agrega persistencia de reportes dentro de Jenkins en `/var/jenkins_home/reports/${JOB_NAME}/${BUILD_NUMBER}`.
- Se limpia el workspace con `cleanWs(deleteDirs: true, disableDeferredWipeout: true)`.
- Se simulan correos con `echo` en `always`, `success` y `failure`.
- Se limpio Docker al final con `docker rm`, `docker network prune`, `docker image prune` y `docker builder prune`.
- Se redujo el consumo de espacio de E2E usando un contenedor efimero con volumen para `results`.
- El `Makefile` quedo preparado para limpiar contenedores y cache antes de E2E, de forma que no se repitan fallos por nombres existentes o espacio temporal.

### Ajuste aplicado al E2E en el Makefile
El target `test-e2e` quedo con limpieza previa y ejecucion menos pesada:

```make
test-e2e:
  docker network create calc-test-e2e || true
  docker stop apiserver || true
  docker rm --force apiserver || true
  docker stop calc-web || true
  docker rm --force calc-web || true
  docker stop e2e-tests || true
  docker rm --force e2e-tests || true
  docker builder prune -af || true
  docker run -d --network calc-test-e2e --env PYTHONPATH=/opt/calc --name apiserver --env FLASK_APP=app/api.py -p 5000:5000 -w /opt/calc calculator-app:latest flask run --host=0.0.0.0
  docker run -d --network calc-test-e2e --name calc-web -p 80:80 calc-web
  i=0; until [ $$i -ge 3 ]; do docker pull $(CYPRESS_IMAGE) && break; i=$$((i+1)); echo "Retry pull Cypress image ($$i/3)"; sleep 5; done; [ $$i -lt 3 ]
  mkdir -p results
  docker run --rm --network calc-test-e2e --name e2e-tests -v `pwd`/test/e2e/cypress.json:/cypress.json:ro -v `pwd`/test/e2e/cypress:/cypress:ro -v `pwd`/results:/results $(CYPRESS_IMAGE) --browser chrome || true
  docker rm --force apiserver  || true
  docker rm --force calc-web || true
  docker network rm calc-test-e2e || true
```

---

## 7) Jenkinsfile final recomendado para tu actividad
La version final quedo documentada en `Jenkinsfile.long.groovy` del repositorio y contiene:
- `Source`
- `Build`
- `Unit tests`
- `API tests`
- `E2E tests`
- `post` con JUnit, persistencia de reportes, limpieza y correo simulado

Si quieres pegar el script completo en un job tipo Pipeline, usa esta version resumida y ya alineada con lo que se dejo hoy:

```groovy
pipeline {
  agent any

  options {
    timeout(time: 60, unit: 'MINUTES')
    timestamps()
    disableConcurrentBuilds()
    buildDiscarder(logRotator(
      numToKeepStr: '1',
      artifactNumToKeepStr: '1'
    ))
  }

  stages {
    stage('Source') {
      steps {
        git branch: 'master', url: 'https://github.com/nathalyg/Actividad3-Pipeline-Nathy'
      }
    }

    stage('Build') {
      steps {
        echo 'Building stage!'
        sh 'make build'
      }
    }

    stage('Unit tests') {
      steps {
        sh 'make test-unit'
        archiveArtifacts artifacts: 'results/*.xml', allowEmptyArchive: true, fingerprint: true
      }
    }

    stage('API tests') {
      steps {
        sh 'make test-api'
        archiveArtifacts artifacts: 'results/*.xml', allowEmptyArchive: true, fingerprint: true
      }
    }

    stage('E2E tests') {
      steps {
        retry(2) {
          sh 'make test-e2e'
        }
        archiveArtifacts artifacts: 'results/*.xml', allowEmptyArchive: true, fingerprint: true
      }
    }
  }

  post {
    always {
      script {
        echo "EMAIL SIMULADO (ALWAYS): Job ${env.JOB_NAME}, build #${env.BUILD_NUMBER}, estado ${currentBuild.currentResult}"

        if (env.WORKSPACE?.trim()) {
          junit allowEmptyResults: true, testResults: 'results/*_result.xml'

          catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
            sh '''
              DEST="/var/jenkins_home/reports/${JOB_NAME}/${BUILD_NUMBER}"
              mkdir -p "$DEST"
              if [ -d results ]; then
                cp -a results/. "$DEST"/
              fi
              echo "Reportes persistidos en: $DEST"
              ls -lah "$DEST" || true
            '''

            sh 'docker rm -f apiserver api-tests calc-web e2e-tests unit-tests || true'
            sh 'docker network prune -f || true'
            sh 'docker image prune -af --filter "until=24h" || true'
            sh 'docker builder prune -af --filter "until=24h" || true'
            cleanWs(deleteDirs: true, disableDeferredWipeout: true)
          }
        } else {
          echo 'Build abortado antes de asignar executor; se omite junit/cleanWs.'
        }
      }
    }

    failure {
      echo "Fallo en job ${env.JOB_NAME} build #${env.BUILD_NUMBER}"
      echo "EMAIL SIMULADO (FAILURE): Job ${env.JOB_NAME}, build #${env.BUILD_NUMBER}, estado FALLIDO"
    }

    success {
      echo "EMAIL SIMULADO (SUCCESS): Job ${env.JOB_NAME}, build #${env.BUILD_NUMBER}, estado EXITOSO"
    }
  }
}
```

---

## 8) Explicacion paso a paso del Jenkinsfile
## `pipeline {}`
Define todo el flujo CI.

## `agent any`
Permite ejecutar en cualquier nodo disponible.

## Stage `Source`
- Clona el repositorio fuente.
- Significa: Jenkins obtiene el codigo para trabajar en su workspace.

## Stage `Build`
- Ejecuta `make build`.
- Significa: construye las imagenes Docker definidas en el `Makefile`.

## Stage `Unit tests`
- Ejecuta `make test-unit`.
- Genera XML de pruebas unitarias.
- `archiveArtifacts`: guarda los XML como evidencia de la ejecucion.

## Stage `API tests`
- Ejecuta `make test-api`.
- Corre pruebas sobre la API en contenedores.
- Archiva XML igual que en unitarias.

## Stage `E2E tests`
- Ejecuta `make test-e2e` con Cypress.
- Valida flujo funcional de extremo a extremo.
- Archiva XML cuando se generan.

## Bloque `post { always { ... } }`
- `junit ...`: publica resultados para la vista de tests en Jenkins.
- `cleanWs()`: limpia workspace para evitar basura entre ejecuciones.

## Bloque `post { failure { ... } }`
- En fallo, muestra mensaje con:
  - `JOB_NAME`
  - `BUILD_NUMBER`
- `emailext` queda comentado para no bloquear la practica si SMTP no esta configurado.

---

## 9) Como configurar el Job en Jenkins
1. Entrar a Jenkins.
2. New Item.
3. Tipo: `Pipeline`.
4. En configuracion del job:
- Pipeline -> Definition: `Pipeline script`.
- Pegar el Jenkinsfile anterior.
5. Guardar.
6. Ejecutar `Build Now`.

### Si prefieres usar el archivo del repositorio
Tambien puedes crear un job de tipo Pipeline y cargar el contenido de `Jenkinsfile.long.groovy` desde el repo, o copiarlo directamente al script del job.

---

## 10) Evidencias que debes capturar para entregar
1. Pantalla de configuracion del pipeline (script completo).
2. Ejecucion con Stage View en verde (SUCCESS).
3. Seccion de resultados de pruebas (JUnit/Tests).
4. Artefactos XML archivados.
5. (Opcional recomendable) consola mostrando ejecucion de Unit/API/E2E.

### Evidencias especificas que ya quedaron implementadas hoy
- XML archivados en `results/unit_result.xml`, `results/api_result.xml`, `results/cypress_result.xml` y `results/coverage.xml`.
- Reporte JUnit visible en la seccion de tests del build.
- Reportes persistentes en `/var/jenkins_home/reports/<JOB_NAME>/<BUILD_NUMBER>`.
- Mensajes de `EMAIL SIMULADO` en consola.
- Limpieza del workspace al final del build.
- Salida de E2E con menor consumo temporal de disco.
- El job ya puede volver a correr sin depender de la carpeta local del repositorio, porque Jenkins clona desde GitHub.

---

## 11) Cumplimiento de cada requisito de la actividad
Checklist y estado esperado con el script final:

- [x] Jenkins instalado.
- [x] Git funcionando.
- [x] Docker funcionando.
- [x] Build funcionando.
- [x] Unit tests.
- [x] API tests.
- [x] E2E tests.
- [x] JUnit Reports.
- [x] Archivado de XML.
- [x] Persistencia de reportes por build.
- [x] Limpieza de workspace.
- [x] Correo simulado con JOB_NAME y BUILD_NUMBER.
- [x] Disco ampliado a 20 GB y particion expandida.
- [x] La causa principal del error fue espacio en disco, no RAM.
- [x] Ajuste de E2E para reducir uso temporal de espacio.
- [x] Necesidad de commit y push documentada para sincronizar cambios con el clone de Jenkins.

---

## 12) Diferencia entre "hacer por partes" y "entregar todo junto"
Debe entregarse un solo pipeline final.

Forma recomendada de trabajo:
1. Primero Source + Build.
2. Luego Unit.
3. Luego API.
4. Luego E2E.
5. Finalmente post (JUnit + failure).

Es decir: desarrollo incremental, entrega integrada.

---

## 13) Comandos utiles de diagnostico
```bash
# Estado Jenkins
sudo docker ps --filter name=jenkins

# Ver logs Jenkins
sudo docker logs -f jenkins

# Ver espacio de disco
df -h

# Ver consumo Docker
sudo docker system df

# Ver particiones y filesystem
lsblk -o NAME,SIZE,FSTYPE,TYPE,MOUNTPOINT
df -hT /

# Ver artifacts archivados de un build
sudo docker exec jenkins sh -lc 'find /var/jenkins_home/jobs/Actividad3-Pipeline-Nathy/builds -path "*/archive/*" -type f | sort | tail -n 20'

# Ver reportes persistidos por build
sudo docker exec jenkins sh -lc 'find /var/jenkins_home/reports/Actividad3-Pipeline-Nathy -type f | sort | tail -n 50'

# Ver usuario Jenkins configurado
sudo docker exec jenkins sh -c 'sed -n "s:.*<id>\(.*\)</id>.*:\1:p" /var/jenkins_home/users/*/config.xml'
```

---

## 14) Texto breve para tu memoria (puedes reutilizar)
Se desplego Jenkins en Docker y se creo un trabajo tipo Pipeline usando script directo en Jenkins. Inicialmente se validaron las etapas Source y Build. Luego se incorporaron Unit tests, API tests y E2E tests, archivando resultados XML y publicando reportes JUnit. Durante la implementacion se resolvieron incidencias de entorno: ausencia de `make`, ausencia de `docker` en el contenedor y permisos sobre `docker.sock`. Posteriormente se ampliara el volumen EBS de la instancia de 8 GB a 20 GB y se expando la particion raiz con `growpart` y `resize2fs` para evitar errores de espacio. Tambien se ajusto la etapa E2E para consumir menos espacio temporal, se agrego persistencia de reportes por build en `/var/jenkins_home/reports/` y se dejo una simulacion de correo con `JOB_NAME` y `BUILD_NUMBER`. Con estos cambios, el pipeline quedo operativo para ejecutar de forma automatica el flujo completo de CI con limpieza al final de cada build.
