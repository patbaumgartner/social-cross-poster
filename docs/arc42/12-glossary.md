# 12. Glossary

| Term | Meaning |
|---|---|
| Workspace / tenant | Logical boundary for a brand's accounts, content, media, settings, jobs, AI usage, and notifications |
| Owner | Sole authenticated operator of one installation; every other person runs their own installation |
| Connection | Workspace-scoped provider authorization/principal and credential lifecycle |
| Account / destination | Independently selectable publishing identity/resource, such as a profile, page, or Telegram channel |
| Platform | Provider/API family; multiple destinations/connections of one platform can coexist |
| Draft / post | Mutable editorial container pointing at an immutable current revision |
| Revision | Immutable content, overrides, media/alt text, and provenance |
| Override | Field replacement at platform or destination level; absent inherits, explicit empty clears |
| Approval | Human confirmation binding workspace, revision, destination identities, and semantic payloads |
| Publication | One approved publishing action with one delivery per selected destination |
| Delivery | A destination's immutable payload and original publishing outcome |
| Attempt | Append-only execution/stage record; retry does not change approved content |
| UNKNOWN | Remote write may have happened; reconcile before resending |
| Remote action | Separate edit, delete, or reconciliation operation preserving the original delivery record |
| PostingTarget | Stateless provider transport strategy using explicit workspace/destination context |
| PostAdapter | Deterministic destination validation and semantic-payload preparation shared by preview/execution |
| Capability snapshot | Verified operations/limits for a particular destination, permissions, instance, and API version |
| Media grant | Revocable, purpose-bound source URL used only while a platform imports approved bytes; never a lasting published image reference |
| AI suggestion | Untrusted editorial proposal, tied to a workspace/base revision, accepted only by the owner |
| Brand profile | Workspace language, tone, protected facts and links used as selected editing context |
| Buildpacks | Cloud Native Buildpacks workflow producing an OCI app image from the application without a default Dockerfile |
| Latest stable baseline | Dated upstream release inventory; resolved artifacts are pinned and retested on update |
| Lease / fencing | Time-bounded job ownership and token preventing stale result writes; not remote exactly-once delivery |
| One-time schedule | One approved post/payload set and chosen instant; creates at most one publication |
| Independent copy | New draft inside the same workspace; editable content/private sources can be reused, but approval/schedule/external IDs/outcomes are cleared |
| Platform-hosted image | Published media stored by the destination and available after temporary source access ends |
| APP_BASE_URL | Operator-configured HTTPS origin per installation for callbacks/media/detail links; stable during authorization flows |
