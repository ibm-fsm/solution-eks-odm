pipeline {
    agent any

    environment {
        KUBECTL = '/var/tmp/kubectl'
        NAMESPACE = 'odm-jenkins'
        HELM_RELEASE = 'odm-lab'
        HELM = '/var/tmp/helm'
        YOUR_EMAIL = 'user@example.com'
    }

    stages {

        stage('Check Cluster') {
            steps {
                script {
                    sh "chmod u+x ${KUBECTL}"
                    withKubeConfig(credentialsId: 'k3s-kubeconfig') {
                        sh "${KUBECTL} get nodes"
                    }
                }
            }
        }

        stage('Create Namespace') {
            steps {
                script {
                    withKubeConfig(credentialsId: 'k3s-kubeconfig') {
                        sh """
                        ${KUBECTL} get namespace ${NAMESPACE} >/dev/null 2>&1 || ${KUBECTL} create namespace ${NAMESPACE}
                        """
                    }
                }
            }
        }

        stage('Deploy Postgres (Lab)') {
            steps {
                script {
                    withCredentials([usernamePassword(
                        credentialsId: 'odm-db-credentials',
                        usernameVariable: 'DB_USER',
                        passwordVariable: 'DB_PASS'
                    )]) {
                        withKubeConfig(credentialsId: 'k3s-kubeconfig') {
                            sh """
echo "Creating Postgres secret..."

${KUBECTL} -n ${NAMESPACE} create secret generic postgres-secret \
  --from-literal=POSTGRES_DB=odmdb \
  --from-literal=POSTGRES_USER=${DB_USER} \
  --from-literal=POSTGRES_PASSWORD=${DB_PASS} \
  --dry-run=client -o yaml | ${KUBECTL} apply -f -

echo "Deploying Postgres securely..."

cat <<EOF | ${KUBECTL} -n ${NAMESPACE} apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: odm-postgres
spec:
  replicas: 1
  selector:
    matchLabels:
      app: odm-postgres
  template:
    metadata:
      labels:
        app: odm-postgres
    spec:
      containers:
      - name: postgres
        image: postgres:13
        envFrom:
        - secretRef:
            name: postgres-secret
        ports:
        - containerPort: 5432
---
apiVersion: v1
kind: Service
metadata:
  name: odm-postgres
spec:
  selector:
    app: odm-postgres
  ports:
  - port: 5432
    targetPort: 5432
EOF

${KUBECTL} -n ${NAMESPACE} rollout status deployment/odm-postgres --timeout=180s
"""
                        }
                    }
                }
            }
        }
        stage('Create ICR Pull Secret') {
          steps {
            script {
              withCredentials([
                string(credentialsId: 'icr-entitlement-key', variable: 'ICR_KEY')
              ]) {
                withKubeConfig(credentialsId: 'k3s-kubeconfig') {
                  sh '''
        echo "Creating docker-registry secret for cp.icr.io..."
        
        NAMESPACE="odm-jenkins"
        SECRET_NAME="icr-secret"
        
        ${KUBECTL} -n ${NAMESPACE} create secret docker-registry ${SECRET_NAME} \
          --docker-server=cp.icr.io \
          --docker-username=cp \
          --docker-password=${ICR_KEY} \
          --docker-email=${YOUR_EMAIL} \
          --dry-run=client -o yaml | ${KUBECTL} apply -f -
        '''
                }
              }
            }
          }
        }
        stage('Setup Helm') {
            steps {
                sh '''
                set -e
        
                echo "Checking if Helm is already installed..."
        
                if [ ! -f "${HELM}" ]; then
                    echo "Helm not found. Installing Helm..."
        
                    curl -fsSL https://get.helm.sh/helm-v3.14.4-linux-amd64.tar.gz -o helm.tar.gz
                    tar -xzf helm.tar.gz
                    mv linux-amd64/helm ${HELM}
                    chmod +x ${HELM}
        
                    echo "Helm installed successfully."
                else
                    echo "Helm already installed. Skipping download."
                fi
        
                echo "Helm version:"
                ${HELM} version
        
                echo "Adding/Updating IBM Helm repo..."
                ${HELM} repo add ibm-helm https://raw.githubusercontent.com/IBM/charts/master/repo/ibm-helm || true
                ${HELM} repo update
                '''
            }
        }
        stage('Prepare Values + Deploy ODM') {
            steps {
                script {
                    withCredentials([
                        string(credentialsId: 'odm-admin-pass', variable: 'ODM_ADMIN_PASS'),
                        string(credentialsId: 'dc-digest', variable: 'DC_DIGEST'),
                        string(credentialsId: 'dr-digest', variable: 'DR_DIGEST'),
                        string(credentialsId: 'dsc-digest', variable: 'DSC_DIGEST'),
                        string(credentialsId: 'dsr-digest', variable: 'DSR_DIGEST')
                    ]) {
                        configFileProvider([configFile(fileId: 'odm-values-lab', variable: 'TEMPLATE_PATH')]) {
                            withKubeConfig(credentialsId: 'k3s-kubeconfig') {
                                sh '''
                                    set -e
        
                                    export NAMESPACE=odm-jenkins
                                    export ODM_HOSTNAME=odm.my-haproxy.gym.lan
                                    export ODM_TLS_SECRET=odm-tls-secret
        
                                    echo "Generating values file with secrets..."
        
                                    sed \
                                      -e "s|__ODM_ADMIN_PASS__|$ODM_ADMIN_PASS|g" \
                                      -e "s|__DC_DIGEST__|$DC_DIGEST|g" \
                                      -e "s|__DR_DIGEST__|$DR_DIGEST|g" \
                                      -e "s|__DSC_DIGEST__|$DSC_DIGEST|g" \
                                      -e "s|__DSR_DIGEST__|$DSR_DIGEST|g" \
                                      -e "s|__NAMESPACE__|$NAMESPACE|g" \
                                      -e "s|__ODM_HOSTNAME__|$ODM_HOSTNAME|g" \
                                      -e "s|__ODM_TLS_SECRET__|$ODM_TLS_SECRET|g" \
                                      "$TEMPLATE_PATH" > "$WORKSPACE/odm-values-lab.yaml"
                                      
                                    echo "Checking rendered values file..."
                                    grep __DC_DIGEST__ $WORKSPACE/odm-values-lab.yaml || echo "DC digest replaced"
                                    grep __ODM_ADMIN_PASS__ $WORKSPACE/odm-values-lab.yaml || echo "Admin pass replaced"

        
                                    echo "Values file created:"
                                    ls -l "$WORKSPACE/odm-values-lab.yaml"
        
                                    echo "Deploying ODM via Helm..."
        
                                    /var/tmp/helm upgrade --install odm-lab ibm-helm/ibm-odm-prod \
                                        --version 25.1.0 \
                                        --namespace ${NAMESPACE} \
                                        -f "$WORKSPACE/odm-values-lab.yaml"
                                '''
                            }
                        }
                    }
                }
            }
        }



    }

    post {
        success {
            echo "FULL ODM stack deployed successfully."
        }
        failure {
            echo "ODM deployment failed."
        }
    }
}
