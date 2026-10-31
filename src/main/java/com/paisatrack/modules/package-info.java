/**
 * Business features. Each module keeps its entities, repositories and services in its root
 * package (internals stay package-private), its REST controllers in {@code web} and its request
 * and response records in {@code dto}. Modules talk to each other only through public services,
 * small {@code *Ref} records and provider interfaces.
 */
package com.paisatrack.modules;
