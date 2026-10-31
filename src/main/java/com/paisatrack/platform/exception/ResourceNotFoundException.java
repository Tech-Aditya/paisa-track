package com.paisatrack.platform.exception;

/** Missing or not owned by the caller. Same response for both, so ids can't be probed. */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String resourceName) {
        super(ErrorCode.RESOURCE_NOT_FOUND, resourceName + " not found");
    }
}
