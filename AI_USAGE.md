# AI usage

I used GitHub Copilot as a development assistant for this project.

I provided the original requirements and supporting documents, discussed possible
solutions, and proposed approaches and changes. Copilot helped with design
discussions, implementation, tests, documentation, and code review.

The work was iterative rather than accepting generated output unchanged. I went
through the proposed solutions and code, asked for explanations, challenged
unnecessary complexity, and requested corrections or simplifications. I made the
decisions about which changes to keep.

Verification combined reviewing the output against the requirements with
automated unit and integration tests. Integration tests exercised the application
against real MySQL and RabbitMQ services, including two application instances,
retries, concurrent requests, reversals, and payment redelivery. I also explored
the API through Swagger UI. 

The custom agent definitions and shared instructions used to guide Copilot are
included in `.github/agents/`. They describe the roles, review expectations, and
working conventions used during development.

AI assistance did not replace my responsibility for understanding, reviewing,
and explaining the submitted solution.
