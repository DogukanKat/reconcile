# notification-service

A downstream consumer that subscribes to authorization events and acts
on them. Concrete shape TBD.

Empty until Phase 2, when retries, DLQ, and consumer error handling
become the focus. The point of having this module is to prove that
payment-service can be consumed by something that wasn't built
together with it.
