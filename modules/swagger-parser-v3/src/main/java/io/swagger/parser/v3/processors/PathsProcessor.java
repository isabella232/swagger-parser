package io.swagger.parser.v3.processors;



import io.swagger.oas.models.OpenAPI;
import io.swagger.oas.models.Operation;
import io.swagger.oas.models.PathItem;
import io.swagger.oas.models.callbacks.Callback;
import io.swagger.oas.models.media.AllOfSchema;
import io.swagger.oas.models.media.AnyOfSchema;
import io.swagger.oas.models.media.ArraySchema;
import io.swagger.oas.models.media.MediaType;
import io.swagger.oas.models.media.OneOfSchema;
import io.swagger.oas.models.media.Schema;
import io.swagger.oas.models.parameters.Parameter;
import io.swagger.oas.models.parameters.RequestBody;
import io.swagger.oas.models.responses.ApiResponse;
import io.swagger.parser.v3.OpenAPIResolver;
import io.swagger.parser.v3.ResolverCache;
import io.swagger.parser.v3.models.RefFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.swagger.parser.v3.util.RefUtils.computeRefFormat;

public class PathsProcessor {

    private final OpenAPI openAPI;
    private final ResolverCache cache;
    private final OpenAPIResolver.Settings settings;
    private final ParameterProcessor parameterProcessor;
    private final OperationProcessor operationProcessor;

    public PathsProcessor(ResolverCache cache, OpenAPI openAPI) {
        this(cache, openAPI, new OpenAPIResolver.Settings());
    }
    public PathsProcessor(ResolverCache cache, OpenAPI openAPI, OpenAPIResolver.Settings settings) {
        this.openAPI = openAPI;
        this.cache = cache;
        this.settings = settings;
        parameterProcessor = new ParameterProcessor(cache, openAPI);
        operationProcessor = new OperationProcessor(cache, openAPI);
    }

    public void processPaths() {
        final Map<String, PathItem> pathMap = openAPI.getPaths();

        if (pathMap == null) {
            return;
        }

        for (String pathStr : pathMap.keySet()) {
            PathItem pathItem = pathMap.get(pathStr);

            addParametersToEachOperation(pathItem);

            if (pathItem.get$ref() != null) {
                RefFormat refFormat = computeRefFormat(pathItem.get$ref());
                PathItem resolvedPath = cache.loadRef(pathItem.get$ref(), refFormat, PathItem.class);

                // TODO: update references to the parent location

                String pathRef = pathItem.get$ref().split("#")[0];
                updateLocalRefs(resolvedPath, pathRef);

                if (resolvedPath != null) {
                    //we need to put the resolved path into swagger object
                    openAPI.path(pathStr, resolvedPath);
                    pathItem = resolvedPath;
                }
            }

            //at this point we can process this path
            final List<Parameter> processedPathParameters = parameterProcessor.processParameters(pathItem.getParameters());
            pathItem.setParameters(processedPathParameters);

            addParametersToEachOperation(pathItem);

            final Map<PathItem.HttpMethod, Operation> operationMap = pathItem.readOperationsMap();

            for (PathItem.HttpMethod httpMethod : operationMap.keySet()) {
                Operation operation = operationMap.get(httpMethod);
                operationProcessor.processOperation(operation);
            }
        }
    }

    private void addParametersToEachOperation(PathItem pathItem) {
        if (settings.addParametersToEachOperation()) {
            List<Parameter> parameters = pathItem.getParameters();

            if (parameters != null) {
                // add parameters to each operation
                List<Operation> operations = pathItem.readOperations();
                if (operations != null) {
                    for (Operation operation : operations) {
                        List<Parameter> parametersToAdd = new ArrayList<>();
                        List<Parameter> existingParameters = operation.getParameters();
                        for (Parameter parameterToAdd : parameters) {
                            boolean matched = false;
                            for (Parameter existingParameter : existingParameters) {
                                if (parameterToAdd.getIn() != null && parameterToAdd.getIn().equals(existingParameter.getIn()) &&
                                        parameterToAdd.getName().equals(existingParameter.getName())) {
                                    matched = true;
                                }
                            }
                            if (!matched) {
                                parametersToAdd.add(parameterToAdd);
                            }
                        }
                        if (parametersToAdd.size() > 0) {
                            operation.getParameters().addAll(0, parametersToAdd);
                        }
                    }
                }
            }
            // remove the shared parameters
            pathItem.setParameters(null);
        }
    }

    protected void updateLocalRefs(PathItem path, String pathRef) {
        if(path.getParameters() != null) {
            List<Parameter> params = path.getParameters();
            for(Parameter param : params) {
                updateLocalRefs(param, pathRef);
            }
        }
        List<Operation> ops = path.readOperations();
        for(Operation op : ops) {
            if(op.getParameters() != null) {
                for (Parameter param : op.getParameters()) {
                    updateLocalRefs(param, pathRef);
                }
            }
            if(op.getResponses() != null) {
                for(ApiResponse response : op.getResponses().values()) {
                    updateLocalRefs(response, pathRef);
                }
            }
            if (op.getRequestBody() != null){
                updateLocalRefs(op.getRequestBody(),pathRef);
            }
            if (op.getCallbacks() != null){
                Map<String,Callback> callbacks = op.getCallbacks();
                for (String name : callbacks.keySet()) {
                    Callback callback = callbacks.get(name);
                    if (callback != null) {
                        for(String callbackName : callback.keySet()) {
                            PathItem pathItem = callback.get(callbackName);
                            updateLocalRefs(pathItem,pathRef);
                        }
                    }
                }
            }
        }
    }

    protected void updateLocalRefs(ApiResponse response, String pathRef) {
        if(response.getContent() != null) {
            Map<String, MediaType> content = response.getContent();
            for (String key: content.keySet()) {
                MediaType mediaType = content.get(key);
                if (mediaType.getSchema() != null) {
                    updateLocalRefs(mediaType.getSchema(), pathRef);
                }
            }
        }
    }

    protected void updateLocalRefs(Parameter param, String pathRef) {
        if(param.getSchema() != null) {
            updateLocalRefs(param.getSchema(), pathRef);
        }
        if(param.getContent() != null) {
            Map<String, MediaType> content = param.getContent();
            for (String key: content.keySet()) {
                MediaType mediaType = content.get(key);
                if (mediaType.getSchema() != null) {
                    updateLocalRefs(mediaType.getSchema(), pathRef);
                }
            }
        }

    }

    protected void updateLocalRefs(RequestBody body, String pathRef) {
        if(body.getContent() != null) {
            Map<String, MediaType> content = body.getContent();
            for (String key: content.keySet()) {
                MediaType mediaType = content.get(key);
                if (mediaType.getSchema() != null) {
                    updateLocalRefs(mediaType.getSchema(), pathRef);
                }
            }
        }
    }

    protected void updateLocalRefs(Schema model, String pathRef) {
        if(model.get$ref() != null) {
            if(isLocalRef(model.get$ref())) {
                model.set$ref(computeLocalRef(model.get$ref(), pathRef));
            }
        }
        else if(model.getProperties() != null) {
            // process properties
            if(model.getProperties() != null) {
                Map<String,Schema> properties = model.getProperties();
                for(String key : properties.keySet()) {
                    Schema property = properties.get(key);
                    if (property != null) {
                        updateLocalRefs(property, pathRef);
                    }
                }
            }
        }
        else if(model instanceof AllOfSchema) {
            AllOfSchema allOfSchema = (AllOfSchema) model;
            for(Schema innerModel : allOfSchema.getAllOf()) {
                updateLocalRefs(innerModel, pathRef);
            }
        }
        else if(model instanceof AnyOfSchema) {
            AnyOfSchema anyOfSchema = (AnyOfSchema) model;
            for(Schema innerModel : anyOfSchema.getAnyOf()) {
                updateLocalRefs(innerModel, pathRef);
            }
        }
        else if(model instanceof OneOfSchema) {
            OneOfSchema oneOfSchema = (OneOfSchema) model;
            for(Schema innerModel : oneOfSchema.getOneOf()) {
                updateLocalRefs(innerModel, pathRef);
            }
        }
        else if(model instanceof ArraySchema) {
            ArraySchema arraySchema = (ArraySchema) model;
            if(arraySchema.getItems() != null) {
                updateLocalRefs(arraySchema.getItems(), pathRef);
            }
        }
    }

    /*protected void updateLocalRefs(Property property, String pathRef) {
        if(property instanceof RefProperty) {
            RefProperty ref = (RefProperty) property;
            if(isLocalRef(ref.get$ref())) {
                ref.set$ref(computeLocalRef(ref.get$ref(), pathRef));
            }
        }
    }*/

    protected boolean isLocalRef(String ref) {
        if(ref.startsWith("#")) {
            return true;
        }
        return false;
    }

    protected String computeLocalRef(String ref, String prefix) {
        return prefix + ref;
    }
}
