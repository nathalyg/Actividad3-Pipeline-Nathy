pipeline {
    agent any

    options {
        // Mantiene la ejecucion acotada y evita builds concurrentes.
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
                // Clona el repositorio principal del proyecto.
                git branch: 'master', url: 'https://github.com/nathalyg/Actividad3-Pipeline-Nathy'
            }
        }

        stage('Build') {
            steps {
                // Construye las imagenes/base necesarias para las pruebas.
                echo 'Building stage!'
                sh 'make build'
            }
        }

        stage('Unit tests') {
            steps {
                // Ejecuta las pruebas unitarias y guarda los XML generados.
                sh 'make test-unit'
                archiveArtifacts artifacts: 'results/*.xml', allowEmptyArchive: true, fingerprint: true
            }
        }

        stage('API tests') {
            steps {
                // Ejecuta las pruebas de API y archiva sus resultados.
                sh 'make test-api'
                archiveArtifacts artifacts: 'results/*.xml', allowEmptyArchive: true, fingerprint: true
            }
        }

        stage('E2E tests') {
            steps {
                // Reintenta las pruebas end-to-end si fallan por un problema temporal.
                retry(2) {
                    sh 'make test-e2e'
                }
                archiveArtifacts artifacts: 'results/*.xml', allowEmptyArchive: true, fingerprint: true
            }
        }

        // stage('Failure simulation') {
        //     steps {
        //         // Etapa temporal para evidenciar el bloque post { failure }.
        //         echo 'Simulando fallo para validar el correo simulado de failure'
        //         sh 'exit 1'
        //     }
        // }
    }

    post {
        always {
            script {
                // Simulacion de correo y evidencia de ejecucion para cualquier estado.
                echo "EMAIL SIMULADO (ALWAYS): Job ${env.JOB_NAME}, build #${env.BUILD_NUMBER}, estado ${currentBuild.currentResult}"

                if (env.WORKSPACE?.trim()) {
                    // Publica los resultados JUnit antes de limpiar el workspace.
                    junit allowEmptyResults: true, testResults: 'results/*_result.xml'

                    catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                        // Conserva una copia persistente de los XML por numero de build.
                        sh '''
                            DEST="/var/jenkins_home/reports/${JOB_NAME}/${BUILD_NUMBER}"
                            mkdir -p "$DEST"
                            if [ -d results ]; then
                              cp -a results/. "$DEST"/
                            fi
                            echo "Reportes persistidos en: $DEST"
                            ls -lah "$DEST" || true
                        '''

                        // Limpieza de contenedores, redes e imagenes temporales.
                        sh 'docker rm -f apiserver api-tests calc-web e2e-tests unit-tests || true'
                        sh 'docker network prune -f || true'
                        sh 'docker image prune -af --filter "until=24h" || true'
                        sh 'docker builder prune -af --filter "until=24h" || true'
                        // Limpia el workspace para no acumular archivos entre builds.
                        cleanWs(deleteDirs: true, disableDeferredWipeout: true)
                    }
                } else {
                    // Cuando el build se aborta antes de obtener executor, no existe workspace utilizable.
                    echo 'Build abortado antes de asignar executor; se omite junit/cleanWs.'
                }
            }
        }

        success {
            // Mensaje de correo simulado en caso de exito.
            echo "EMAIL SIMULADO (SUCCESS): Job ${env.JOB_NAME}, build #${env.BUILD_NUMBER}, estado EXITOSO"
        }

        failure {
            // Mensaje de correo simulado en caso de fallo.
            echo "EMAIL SIMULADO (FAILURE): Job ${env.JOB_NAME}, build #${env.BUILD_NUMBER}, estado FALLIDO"
            // emailext(
            //   to: 'correo@dominio.com',
            //   subject: "Fallo en ${env.JOB_NAME} #${env.BUILD_NUMBER}",
            //   body: "El trabajo ${env.JOB_NAME} terminó con fallo en la ejecución #${env.BUILD_NUMBER}."
            // )
        }
    }
}