package com.patchlens.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Seeds fixture context chunks for sample PRs at startup.
 * Each sample repo (example/demo-app, example/user-service, example/saas-platform)
 * gets 2 pre-written files indexed so that RAG retrieval returns meaningful
 * context during sample PR analysis instead of an empty result set.
 *
 * Runs once via ApplicationReadyEvent; skips repos that are already indexed.
 */
@Component
public class SampleContextSeeder {

    private static final Logger log = LoggerFactory.getLogger(SampleContextSeeder.class);

    private final ContextIndexingService contextIndexingService;

    public SampleContextSeeder(ContextIndexingService contextIndexingService) {
        this.contextIndexingService = contextIndexingService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedSampleContext() {
        seedRepo("example", "demo-app",        DEMO_APP_FILES);
        seedRepo("example", "user-service",    USER_SERVICE_FILES);
        seedRepo("example", "saas-platform",   SAAS_PLATFORM_FILES);
    }

    private void seedRepo(String owner, String repo, Map<String, String> files) {
        if (contextIndexingService.isIndexed(owner, repo)) {
            log.debug("Sample context already seeded for {}/{}, skipping", owner, repo);
            return;
        }
        log.info("Seeding sample context for {}/{} ({} files)", owner, repo, files.size());
        try {
            files.forEach((path, content) ->
                    contextIndexingService.indexTextContent(owner, repo, path, content));
        } catch (Exception e) {
            log.warn("Failed to seed sample context for {}/{}: {}", owner, repo, e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------
    // Fixture content — realistic descriptions of each sample repo's architecture
    // ---------------------------------------------------------------------------

    /** example/demo-app — Redis Session Cache PR */
    private static final Map<String, String> DEMO_APP_FILES = Map.of(

        "README.md", """
                # demo-app

                demo-app is a multi-tenant Spring Boot web application.
                Authentication is cookie-based: on login, the server generates a UUID session token
                and stores it in an in-memory ConcurrentHashMap keyed by that token.
                The cookie is HttpOnly and scoped to the application domain.

                Session data includes: authenticated user ID, user role (USER / ADMIN),
                last-seen timestamp, and a list of up to 20 recent activity events.
                Sessions expire after 30 minutes of inactivity; a scheduled task runs every
                5 minutes to evict stale entries from the map.

                The in-memory store is not replicated across JVM instances, so horizontal
                scaling requires stickiness or migration to a shared external store such as Redis.
                """,

        "src/main/java/com/example/session/SessionConfig.java", """
                // SessionConfig.java
                // Configures the session store used across the application.
                //
                // Current implementation: InMemorySessionStore backed by ConcurrentHashMap.
                // TTL is controlled by session.ttl-minutes (default 30).
                //
                // To migrate to Redis:
                //   1. Replace InMemorySessionStore with RedisSessionStore.
                //   2. Ensure session objects implement Serializable (or use JSON serialization).
                //   3. Set spring.data.redis.host / port in application.properties.
                //   4. Remove the @Scheduled eviction task — Redis TTL handles expiry natively.
                //
                // All session access goes through SessionManager; no controller touches the
                // store directly, so the swap is localised to this config class.
                """
    );

    /** example/user-service — Auth DB Migration PR */
    private static final Map<String, String> USER_SERVICE_FILES = Map.of(

        "README.md", """
                # user-service

                user-service owns authentication and authorisation for the platform.

                Authentication: password-based using BCrypt (cost factor 12).
                The `users` table schema:
                  id UUID PRIMARY KEY
                  email VARCHAR UNIQUE NOT NULL
                  password_hash VARCHAR        -- BCrypt hash, active
                  legacy_plain_text_password VARCHAR  -- retained from early prototype, MUST be dropped
                  created_at TIMESTAMP
                  updated_at TIMESTAMP

                Authorisation: role-based (RBAC). Roles are stored in the `user_roles` join table
                referencing `roles(id)`. Current roles: USER, ADMIN, SUPPORT.

                Planned migration: replace password-based auth with OAuth 2.0 using Spring Security
                OAuth2 Client. Supported providers: Google, GitHub.
                After migration the `password_hash` and `legacy_plain_text_password` columns
                will both be dropped.
                """,

        "src/main/java/com/example/auth/AuthService.java", """
                // AuthService.java
                // Handles login, logout, and session lifecycle.
                //
                // Current login flow:
                //   1. Look up user by email.
                //   2. Verify supplied password against password_hash using BCryptPasswordEncoder.
                //   3. On success, create a new session and return the session token.
                //
                // Legacy column note:
                //   legacy_plain_text_password was added during the v0.1 prototype and is no
                //   longer written to. It is safe to DROP after confirming no downstream queries
                //   reference it. Include the DROP in the same migration that adds the OAuth
                //   provider columns (provider VARCHAR, provider_user_id VARCHAR).
                //
                // OAuth migration plan:
                //   - Add oauth2Login() to Spring Security filter chain.
                //   - On first OAuth sign-in, create a user row with provider + provider_user_id;
                //     password_hash will be null for OAuth-only accounts.
                //   - Existing password accounts can link an OAuth provider via account settings.
                """
    );

    /** example/saas-platform — Stripe Checkout PR */
    private static final Map<String, String> SAAS_PLATFORM_FILES = Map.of(

        "README.md", """
                # saas-platform

                saas-platform is a subscription SaaS application with three tiers:
                  Free  — 0 USD/month, limited to 3 projects
                  Pro   — 29 USD/month, unlimited projects + priority support
                  Enterprise — custom pricing, SSO + audit logs

                Current billing: invoices are generated manually and stored in the `invoices`
                table; customers pay via bank transfer. There is no automated payment processing.

                Planned integration: Stripe Checkout for automated subscription billing.
                Environment variables required:
                  STRIPE_SECRET_KEY       — server-side API key
                  STRIPE_WEBHOOK_SECRET   — used to verify Stripe-Signature header on webhooks

                Critical webhook events to handle:
                  checkout.session.completed       — activate subscription after payment
                  customer.subscription.updated    — handle plan upgrades / downgrades
                  customer.subscription.deleted    — downgrade to Free tier
                  invoice.payment_failed           — notify user, retry logic
                """,

        "src/main/java/com/example/billing/BillingService.java", """
                // BillingService.java
                // Manages subscription state and payment events.
                //
                // Current state: subscriptions are stored in the `subscriptions` table with
                // columns (id, user_id, tier, status, created_at). Status values: ACTIVE, CANCELLED.
                // No external payment processor is integrated yet.
                //
                // Stripe integration requirements:
                //   1. Verify webhook signature before processing any event:
                //      Stripe.constructEvent(payload, sigHeader, webhookSecret)
                //   2. All webhook handlers must be idempotent — store processed Stripe event IDs
                //      in a `processed_stripe_events` table and skip duplicates.
                //   3. On checkout.session.completed: set subscription status to ACTIVE,
                //      store stripe_customer_id and stripe_subscription_id on the user record.
                //   4. On invoice.payment_failed: send email notification; after 3 failures
                //      set status to PAST_DUE and restrict Pro features.
                //
                // Do not store raw card data — Stripe handles PCI compliance.
                """
    );
}
