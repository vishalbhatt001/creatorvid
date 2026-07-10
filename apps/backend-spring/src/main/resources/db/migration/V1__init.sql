-- Reproduces packages/db/prisma/schema.prisma exactly: same table names, column
-- names/casing (quoted to preserve Prisma's camelCase), types, indexes and foreign
-- keys, so this schema is byte-compatible with the existing Prisma-managed database
-- during the side-by-side migration.
--
-- Deviation: Prisma's enums (GenerationStatus, RenderBlockPhase, CreditTxnType,
-- PaymentStatus) are declared here as text + CHECK constraints instead of native
-- Postgres ENUM types. The stored values are identical strings either way; this
-- avoids Hibernate's native-enum JDBC casting (which lowercases/derives the
-- Postgres type name from the Java enum in ways that don't reliably match a
-- quoted mixed-case type name) in favor of plain @Enumerated(STRING) mapping.

-- ---------------------------------------------------------------------------
-- Auth models (better-auth shape)
-- ---------------------------------------------------------------------------

CREATE TABLE "user" (
  "id"            text PRIMARY KEY,
  "name"          text NOT NULL,
  "email"         text NOT NULL UNIQUE,
  "emailVerified" boolean NOT NULL DEFAULT false,
  "image"         text,
  "role"          text NOT NULL DEFAULT 'user',
  "credits"       integer NOT NULL DEFAULT 0,
  "createdAt"     timestamp(3) NOT NULL DEFAULT now(),
  "updatedAt"     timestamp(3) NOT NULL DEFAULT now()
);

CREATE TABLE "session" (
  "id"        text PRIMARY KEY,
  "expiresAt" timestamp(3) NOT NULL,
  "token"     text NOT NULL UNIQUE,
  "createdAt" timestamp(3) NOT NULL DEFAULT now(),
  "updatedAt" timestamp(3) NOT NULL DEFAULT now(),
  "ipAddress" text,
  "userAgent" text,
  "userId"    text NOT NULL REFERENCES "user"("id") ON DELETE CASCADE
);

CREATE TABLE "account" (
  "id"                    text PRIMARY KEY,
  "accountId"             text NOT NULL,
  "providerId"            text NOT NULL,
  "userId"                text NOT NULL REFERENCES "user"("id") ON DELETE CASCADE,
  "accessToken"           text,
  "refreshToken"          text,
  "idToken"               text,
  "accessTokenExpiresAt"  timestamp(3),
  "refreshTokenExpiresAt" timestamp(3),
  "scope"                 text,
  "password"              text,
  "createdAt"             timestamp(3) NOT NULL DEFAULT now(),
  "updatedAt"             timestamp(3) NOT NULL DEFAULT now()
);

CREATE TABLE "verification" (
  "id"         text PRIMARY KEY,
  "identifier" text NOT NULL,
  "value"      text NOT NULL,
  "expiresAt"  timestamp(3) NOT NULL,
  "createdAt"  timestamp(3) NOT NULL DEFAULT now(),
  "updatedAt"  timestamp(3) NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Core generation models
-- ---------------------------------------------------------------------------

CREATE TABLE "video" (
  "id"                 text PRIMARY KEY,
  "userId"             text NOT NULL REFERENCES "user"("id") ON DELETE CASCADE,
  "status"             text NOT NULL DEFAULT 'PENDING'
                         CHECK ("status" IN ('PENDING','IN_PROGRESS','COMPLETED','FAILED')),
  "prompt"             text NOT NULL,
  "model"              text NOT NULL,
  "duration"           integer,
  "resolution"         text,
  "aspectRatio"        text,
  "generateAudio"      boolean,
  "startFrameKey"      text,
  "endFrameKey"        text,
  "referenceFrameKeys" text[] NOT NULL DEFAULT '{}',
  "videoKey"           text,
  "providerJobId"      text,
  "cost"               double precision,
  "error"              text,
  "createdAt"          timestamp(3) NOT NULL DEFAULT now(),
  "updatedAt"          timestamp(3) NOT NULL DEFAULT now()
);
CREATE INDEX "video_userId_idx" ON "video"("userId");

CREATE TABLE "image" (
  "id"                 text PRIMARY KEY,
  "userId"             text NOT NULL REFERENCES "user"("id") ON DELETE CASCADE,
  "status"             text NOT NULL DEFAULT 'PENDING'
                         CHECK ("status" IN ('PENDING','IN_PROGRESS','COMPLETED','FAILED')),
  "prompt"             text NOT NULL,
  "model"              text NOT NULL,
  "resolution"         text,
  "aspectRatio"        text,
  "referenceImageKeys" text[] NOT NULL DEFAULT '{}',
  "imageKey"           text,
  "providerJobId"      text,
  "cost"               double precision,
  "error"              text,
  "createdAt"          timestamp(3) NOT NULL DEFAULT now(),
  "updatedAt"          timestamp(3) NOT NULL DEFAULT now()
);
CREATE INDEX "image_userId_idx" ON "image"("userId");

CREATE TABLE "face_swap" (
  "id"        text PRIMARY KEY,
  "userId"    text NOT NULL REFERENCES "user"("id") ON DELETE CASCADE,
  "status"    text NOT NULL DEFAULT 'PENDING'
               CHECK ("status" IN ('PENDING','IN_PROGRESS','COMPLETED','FAILED')),
  "sourceKey" text NOT NULL,
  "targetKey" text NOT NULL,
  "outputKey" text,
  "error"     text,
  "createdAt" timestamp(3) NOT NULL DEFAULT now(),
  "updatedAt" timestamp(3) NOT NULL DEFAULT now()
);
CREATE INDEX "face_swap_userId_idx" ON "face_swap"("userId");

-- ---------------------------------------------------------------------------
-- Video templates
-- ---------------------------------------------------------------------------

CREATE TABLE "avatar" (
  "id"              text PRIMARY KEY,
  "userId"          text NOT NULL REFERENCES "user"("id") ON DELETE CASCADE,
  "status"          text NOT NULL DEFAULT 'COMPLETED'
                      CHECK ("status" IN ('PENDING','IN_PROGRESS','COMPLETED','FAILED')),
  "name"            text NOT NULL,
  "sourceImageKeys" text[] NOT NULL DEFAULT '{}',
  "faceKey"         text,
  "error"           text,
  "createdAt"       timestamp(3) NOT NULL DEFAULT now(),
  "updatedAt"       timestamp(3) NOT NULL DEFAULT now()
);
CREATE INDEX "avatar_userId_idx" ON "avatar"("userId");

CREATE TABLE "template" (
  "id"              text PRIMARY KEY,
  "creatorId"       text NOT NULL REFERENCES "user"("id") ON DELETE CASCADE,
  "name"            text NOT NULL,
  "description"     text,
  "avatarSlots"     integer NOT NULL DEFAULT 1,
  "avatarIds"       text[] NOT NULL DEFAULT '{}',
  "published"       boolean NOT NULL DEFAULT false,
  "thumbnailPrompt" text,
  "previewVideoKey" text,
  "thumbnailKey"    text,
  "createdAt"       timestamp(3) NOT NULL DEFAULT now(),
  "updatedAt"       timestamp(3) NOT NULL DEFAULT now()
);
CREATE INDEX "template_creatorId_idx" ON "template"("creatorId");

CREATE TABLE "template_block" (
  "id"              text PRIMARY KEY,
  "templateId"      text NOT NULL REFERENCES "template"("id") ON DELETE CASCADE,
  "order"           integer NOT NULL,
  "startSec"        double precision NOT NULL,
  "endSec"          double precision NOT NULL,
  "track"           integer NOT NULL DEFAULT 0,
  "prompt"          text NOT NULL,
  "model"           text NOT NULL,
  "duration"        integer,
  "resolution"      text,
  "aspectRatio"     text,
  "startImageKey"   text,
  "endImageKey"     text,
  "swappedStartKey" text,
  "swappedEndKey"   text,
  "videoKey"        text,
  "sourceVideoKey"  text,
  "cropStart"       double precision NOT NULL DEFAULT 0,
  "cropEnd"         double precision,
  "linkGroupId"     text,
  "faceSwapStart"   boolean NOT NULL DEFAULT false,
  "faceSwapEnd"     boolean NOT NULL DEFAULT false,
  "avatarSlot"      integer NOT NULL DEFAULT 0,
  "swapContext"     text,
  "lipsync"         boolean NOT NULL DEFAULT false,
  "swapModel"       text,
  "createdAt"       timestamp(3) NOT NULL DEFAULT now(),
  "updatedAt"       timestamp(3) NOT NULL DEFAULT now()
);
CREATE INDEX "template_block_templateId_idx" ON "template_block"("templateId");
CREATE INDEX "template_block_linkGroupId_idx" ON "template_block"("linkGroupId");

CREATE TABLE "template_audio_clip" (
  "id"         text PRIMARY KEY,
  "templateId" text NOT NULL REFERENCES "template"("id") ON DELETE CASCADE,
  "order"      integer NOT NULL DEFAULT 0,
  "startSec"   double precision NOT NULL,
  "endSec"     double precision NOT NULL,
  "track"      integer NOT NULL DEFAULT 0,
  "audioKey"   text NOT NULL,
  "name"       text,
  "duration"   double precision NOT NULL,
  "cropStart"  double precision NOT NULL DEFAULT 0,
  "cropEnd"    double precision,
  "createdAt"  timestamp(3) NOT NULL DEFAULT now(),
  "updatedAt"  timestamp(3) NOT NULL DEFAULT now()
);
CREATE INDEX "template_audio_clip_templateId_idx" ON "template_audio_clip"("templateId");

CREATE TABLE "template_render" (
  "id"           text PRIMARY KEY,
  "templateId"   text NOT NULL REFERENCES "template"("id") ON DELETE CASCADE,
  "userId"       text NOT NULL REFERENCES "user"("id") ON DELETE CASCADE,
  "status"       text NOT NULL DEFAULT 'PENDING'
                   CHECK ("status" IN ('PENDING','IN_PROGRESS','COMPLETED','FAILED')),
  "avatarIds"    text[] NOT NULL DEFAULT '{}',
  "videoKey"     text,
  "thumbnailKey" text,
  "cost"         double precision,
  "error"        text,
  "createdAt"    timestamp(3) NOT NULL DEFAULT now(),
  "updatedAt"    timestamp(3) NOT NULL DEFAULT now()
);
CREATE INDEX "template_render_templateId_idx" ON "template_render"("templateId");
CREATE INDEX "template_render_userId_idx" ON "template_render"("userId");

CREATE TABLE "template_render_block" (
  "id"              text PRIMARY KEY,
  "renderId"        text NOT NULL REFERENCES "template_render"("id") ON DELETE CASCADE,
  "blockId"         text NOT NULL,
  "order"           integer NOT NULL,
  "startSec"        double precision NOT NULL,
  "endSec"          double precision NOT NULL,
  "label"           text,
  "phase"           text NOT NULL DEFAULT 'QUEUED'
                      CHECK ("phase" IN ('QUEUED','FACE_SWAP','VIDEO_GENERATION','RETRYING',
                        'STITCHING','COMPLETED','REUSED','FELL_BACK','FAILED')),
  "attempt"         integer NOT NULL DEFAULT 0,
  "error"           text,
  "videoKey"        text,
  "swappedStartKey" text,
  "swappedEndKey"   text,
  "createdAt"       timestamp(3) NOT NULL DEFAULT now(),
  "updatedAt"       timestamp(3) NOT NULL DEFAULT now()
);
CREATE INDEX "template_render_block_renderId_idx" ON "template_render_block"("renderId");

-- Implicit many-to-many join table (Prisma default naming: "_<A>To<B>" alphabetical).
CREATE TABLE "_AvatarToTemplateRender" (
  "A" text NOT NULL REFERENCES "avatar"("id") ON DELETE CASCADE ON UPDATE CASCADE,
  "B" text NOT NULL REFERENCES "template_render"("id") ON DELETE CASCADE ON UPDATE CASCADE
);
CREATE UNIQUE INDEX "_AvatarToTemplateRender_AB_unique" ON "_AvatarToTemplateRender"("A", "B");
CREATE INDEX "_AvatarToTemplateRender_B_index" ON "_AvatarToTemplateRender"("B");

-- ---------------------------------------------------------------------------
-- Credits & billing
-- ---------------------------------------------------------------------------

CREATE TABLE "credit_transaction" (
  "id"            text PRIMARY KEY,
  "userId"        text NOT NULL REFERENCES "user"("id") ON DELETE CASCADE,
  "type"          text NOT NULL
                    CHECK ("type" IN ('PURCHASE','SPEND','REFUND','BONUS','ADJUSTMENT')),
  "amount"        integer NOT NULL,
  "balanceAfter"  integer NOT NULL,
  "description"   text,
  "referenceType" text,
  "referenceId"   text,
  "createdAt"     timestamp(3) NOT NULL DEFAULT now()
);
CREATE INDEX "credit_transaction_userId_idx" ON "credit_transaction"("userId");
CREATE INDEX "credit_transaction_referenceType_referenceId_idx" ON "credit_transaction"("referenceType", "referenceId");

CREATE TABLE "payment" (
  "id"                text PRIMARY KEY,
  "userId"            text NOT NULL REFERENCES "user"("id") ON DELETE CASCADE,
  "status"            text NOT NULL DEFAULT 'CREATED'
                        CHECK ("status" IN ('CREATED','PAID','FAILED')),
  "packId"            text NOT NULL,
  "amount"            integer NOT NULL,
  "currency"          text NOT NULL DEFAULT 'INR',
  "credits"           integer NOT NULL,
  "razorpayOrderId"   text NOT NULL UNIQUE,
  "razorpayPaymentId" text,
  "razorpaySignature" text,
  "createdAt"         timestamp(3) NOT NULL DEFAULT now(),
  "updatedAt"         timestamp(3) NOT NULL DEFAULT now()
);
CREATE INDEX "payment_userId_idx" ON "payment"("userId");
