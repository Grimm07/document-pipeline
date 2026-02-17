# Why is this project a multi-module build?
This project shares domain logic across the API layer, the database layer, the queue layer, and the persistence layer. While the core domain could be implemented with a shared JAR approach, that would require multiple projects and deployments to a maven (or similar) repository.

Due to the added complexity of sharing the JAR and the fact that there were no cross-team dependencies, I opted for a multi-module build.

# What approaches are used to speed up the build?

The build utilizes the multi-module build, which uses gradle's caching mechanism to only re-build the modules that have changed since the previous build. Additionally, we utilize the configuration caching functionality. This is separate from the build cache, and only applies to gradle's configuration stage.

## Cache Issues
While caching artifacts can lead to build problems, it significantly speeds up the build time. Thus, as long as the project maintainers / users are aware of the caching, it is fine to use.

# Why does this project contain the ml-service and UI as well as the backend?

This was a design decision I made to simplify the development process. In practice, you would separate out these components into their own project.

# Things this project does not include (at least, currently)
- IaC: This project is intended for a single deployment on a local machine. No cloud infrastructure is required, therefore there is no terraform code supplied.
- Authentication: In practice, you would want a OAuth / MFA type authentication layer added to the UI, which would sit between the front and back ends. This was intentionally left out for simplicity. 
- Replication: The storage layer here is local and assumes no replication. This would be a huge concern for a distributed system.
- Partitioning: Similarly, there is no partitioning strategy employed for this application. This is another concern that would arise in a distributed system.
- Application-level Caching (backend): While the frontend does employ caching via Tanstack-Query, there is no caching layer in the backend. This could reduce latency depending on the production requirements
- Log-Ingestion: For a full product solution, there would ideally be some form of log ingestion for metrics related tasks
- Interoperability w/ Java: While possible, this project assumes we are working in a fully kotlin backend. This may introduce issues if introducing Java packages like Lettuce due to dependencies like kotlinx.serialization, which requires the @Serializable annotation to allow annotations (and does not allow annotating type Any).
- Randomized Testing: Due to input validation, ideally we would add randomized input testing. This creates random input values to test edge cases for holes in our regex & other input constraints.
- Mutation Testing: This would ensure our test suite is flush enough to catch errors when mutations are added to our source code.

