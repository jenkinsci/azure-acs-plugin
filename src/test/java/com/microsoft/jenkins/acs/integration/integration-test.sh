#!/usr/bin/env bash

show_help() {
    cat <<EOF
In the project root directory, run the following command and pass in the service principal details in parameters.

    bash src/test/java/com/microsoft/jenkins/acs/integration/integration-test.sh -s <subsctiption-id> -c <client-id> -t <tenant>

The script will prompt to input the service principal secrets (you can also pass in with --client-secret).
It will create a resource group, create ACS with Kubernetes, DCOS, and Swarm as orchestrator, together with an ACR
instance in the resource group, and then start the tests. When the tests finishes, it will clean up the resource group created.

To simply the clean up process, all the resources will be created in the same resource group.
You can also pass in the existing resource group, ACS, ACR names and the script will reuse existing one.
If the resource group isn't created by the script, it will not be deleted after the tests.
EOF
    exit 0
}

if [[ ! -f pom.xml ]]; then
    show_help
fi

set -ex

export SKIP_CLEAN=false

while [[ $# -gt 0 ]]; do
    key="$1"

    case "$key" in
        -s|--subscription)
            export ACS_TEST_SUBSCRIPTION_ID="$2"
            shift; shift
            ;;
        -c|--client)
            export ACS_TEST_CLIENT_ID="$2"
            shift; shift
            ;;
        --client-secret)
            export ACS_TEST_CLIENT_SECRET="$2"
            shift; shift
            ;;
        -t|--tenant)
            export ACS_TEST_TENANT="$2"
            shift; shift
            ;;
        -l|--location)
            export ACS_TEST_LOCATION="$2"
            shift; shift
            ;;
        -r|--resource-group)
            export ACS_TEST_RESOURCE_GROUP="$2"
            shift; shift
            ;;
        --k8s-name)
            export ACS_TEST_KUBERNETES_NAME="$2"
            shift; shift
            ;;
        --dcos-name)
            export ACS_TEST_DCOS_NAME="$2"
            shift; shift
            ;;
        --swarm-name)
            export ACS_TEST_SWARM_NAME="$2"
            shift; shift
            ;;
        --acr-name)
            export ACS_TEST_ACR_NAME="$2"
            shift; shift
            ;;
        --admin-user)
            export ACS_TEST_ADMIN_USER="$2"
            shift; shift
            ;;
        --private-key-file)
            export ACS_TEST_PRIVATE_KEY_PATH="$2"
            shift; shift
            ;;
        --public-key-file)
            export ACS_TEST_PUBLIC_KEY_PATH="$2"
            shift; shift
            ;;
        --skip-clean)
            export SKIP_CLEAN=true
            shift
            ;;
        --help)
            show_help
            ;;
        *)
            echo "Unknown parameter '$key'" >&2
            show_help
            ;;
    esac
done

# allow the service principal client secret to be entered from the terminal
if [[ -z "$ACS_TEST_CLIENT_SECRET" ]]; then
    echo -n "Service principal client secret: "
    read -s ACS_TEST_CLIENT_SECRET
    export ACS_TEST_CLIENT_SECRET
fi

# require service principal details
if [[ -z "$ACS_TEST_SUBSCRIPTION_ID" ]] || [[ -z "$ACS_TEST_CLIENT_ID" ]] || [[ -z "$ACS_TEST_CLIENT_SECRET" ]] || [[ -z "$ACS_TEST_TENANT" ]]; then
    echo "Service principal details not specified. Terminate" >&2
    exit -1
fi


# require Azure CLI login
if ! az account list >/dev/null; then
    echo "Azure CLI is not logged in" >&2
    exit -1
fi

# require docker environment
if ! which docker >/dev/null; then
    echo "Docker is not available in the host" >&2
    exit -1
fi

# common suffix for the names
suffix=$(xxd -p -l 4 /dev/urandom)

# construct resource group name
if [[ -z "$ACS_TEST_RESOURCE_GROUP" ]]; then
    export ACS_TEST_RESOURCE_GROUP="acs-test-$suffix"
    # default to SEA
    if [[ -z "$ACS_TEST_LOCATION" ]]; then
        export ACS_TEST_LOCATION=SoutheastAsia
    fi
else
    # require resource group location
    if [[ -z "$ACS_TEST_LOCATION" ]]; then
        echo "Location is not specified" >&2
        exit -1
    fi
fi

# ACR name
if [[ -z "$ACS_TEST_ACR_NAME" ]]; then
    export ACS_TEST_ACR_NAME="acr$suffix"
fi

# Kubernetes ACS name
if [[ -z "$ACS_TEST_KUBERNETES_NAME" ]]; then
    export ACS_TEST_KUBERNETES_NAME="k8s-$suffix"
fi

# DCOS ACS name
if [[ -z "$ACS_TEST_DCOS_NAME" ]]; then
    export ACS_TEST_DCOS_NAME="dcos-$suffix"
fi

# SWARM ACS name
if [[ -z "$ACS_TEST_SWARM_NAME" ]]; then
    export ACS_TEST_SWARM_NAME="swarm-$suffix"
fi

# SSH
if [[ -z "$ACS_TEST_ADMIN_USER" ]]; then
    export ACS_TEST_ADMIN_USER=azureuser
fi
if [[ -z "$ACS_TEST_PRIVATE_KEY_PATH" ]]; then
    export ACS_TEST_PRIVATE_KEY_PATH="$(readlink -f ~/.ssh/id_rsa)"
fi
if [[ -z "$ACS_TEST_PUBLIC_KEY_PATH" ]]; then
    export ACS_TEST_PUBLIC_KEY_PATH="$(readlink -f ~/.ssh/id_rsa.pub)"
fi

if [[ ! -f "$ACS_TEST_PUBLIC_KEY_PATH" ]] || [[ ! -f "$ACS_TEST_PRIVATE_KEY_PATH" ]]; then
    echo "Cannot find SSH key files: '$ACS_TEST_PUBLIC_KEY_PATH', '$ACS_TEST_PRIVATE_KEY_PATH'" >&2
    echo "Run ssh-keygen to create them" >&2
    exit -1
fi

export integration_test_script_pid=$$

post_clean_up() {
    set +x
    # wait the testing process to finish
    while ps -p "$integration_test_script_pid" >/dev/null; do
        sleep 0.5
    done
    set -x

    echo "Clean up resource group $ACS_TEST_RESOURCE_GROUP"
    az group delete --yes --no-wait --name "$ACS_TEST_RESOURCE_GROUP"
}

group_exists=$(az group exists --name "$ACS_TEST_RESOURCE_GROUP")
if [[ "false" == "$group_exists" ]]; then
    az group create --name "$ACS_TEST_RESOURCE_GROUP" --location "$ACS_TEST_LOCATION"

    if [[ "$SKIP_CLEAN" != "true" ]]; then
        echo "Registering the clean up process..."
        # spwan the clean up process and detach if from the current process (its parent process)
        post_clean_up & disown
    fi
fi

k8s_info=$(az acs show --resource-group "$ACS_TEST_RESOURCE_GROUP" --name "$ACS_TEST_KUBERNETES_NAME")
if [[ -z "$k8s_info" ]]; then
    az acs create --orchestrator-type kubernetes --resource-group "$ACS_TEST_RESOURCE_GROUP" --name "$ACS_TEST_KUBERNETES_NAME" --agent-count 2 --ssh-key-value "$ACS_TEST_PUBLIC_KEY_PATH" &
    k8s_pid=$!
fi

dcos_info=$(az acs show --resource-group "$ACS_TEST_RESOURCE_GROUP" --name "$ACS_TEST_DCOS_NAME")
if [[ -z "$dcos_info" ]]; then
    az acs create --orchestrator-type dcos --resource-group "$ACS_TEST_RESOURCE_GROUP" --name "$ACS_TEST_DCOS_NAME" --agent-count 2 --ssh-key-value "$ACS_TEST_PUBLIC_KEY_PATH" &
    dcos_pid=$!
fi

swarm_info=$(az acs show --resource-group "$ACS_TEST_RESOURCE_GROUP" --name "$ACS_TEST_SWARM_NAME")
if [[ -z "$swarm_info" ]]; then
    az acs create --orchestrator-type swarm --resource-group "$ACS_TEST_RESOURCE_GROUP" --name "$ACS_TEST_SWARM_NAME" --agent-count 2 --ssh-key-value "$ACS_TEST_PUBLIC_KEY_PATH" &
    swarm_pid=$!
fi

acr_info=$(az acr show --resource-group "$ACS_TEST_RESOURCE_GROUP" --name "$ACS_TEST_ACR_NAME")
if [[ -z "$acr_info" ]]; then
    az acr create --resource-group "$ACS_TEST_RESOURCE_GROUP" --name "$ACS_TEST_ACR_NAME" --sku Basic --admin-enabled true
    az role assignment create \
        --scope "/subscriptions/$ACS_TEST_SUBSCRIPTION_ID/resourcegroups/$ACS_TEST_RESOURCE_GROUP/providers/Microsoft.ContainerRegistry/registries/$ACS_TEST_ACR_NAME" \
        --role Owner \
        --assignee "$ACS_TEST_CLIENT_ID"
fi

export ACS_TEST_DOCKER_REPOSITORY=$(az acr show --resource-group $ACS_TEST_RESOURCE_GROUP --name $ACS_TEST_ACR_NAME --query "loginServer" --output tsv)
export ACS_TEST_DOCKER_USERNAME="$ACS_TEST_ACR_NAME"
export ACS_TEST_DOCKER_PASSWORD=$(az acr credential show --name $ACS_TEST_ACR_NAME --query "passwords[0].value" --output tsv)
export ACS_TEST_DOCKER_REGISTRY="http://$ACS_TEST_DOCKER_REPOSITORY"

if [[ -z "$ACS_TEST_DOCKER_REPOSITORY" ]]; then
    echo "Failed to query ACR information" >&2
    exit -1
fi

docker pull nginx
docker login -u "$ACS_TEST_DOCKER_USERNAME" -p "$ACS_TEST_DOCKER_PASSWORD" "$ACS_TEST_DOCKER_REPOSITORY"
docker tag nginx:latest "$ACS_TEST_DOCKER_REPOSITORY/acs-test-private"
docker push "$ACS_TEST_DOCKER_REPOSITORY/acs-test-private"

[[ -n "$k8s_pid" ]] && wait "$k8s_pid"
[[ -n "$dcos_pid" ]] && wait "$dcos_pid"
[[ -n "$swarm_pid" ]] && wait "$swarm_pid"

mvn clean test-compile failsafe:integration-test failsafe:verify

