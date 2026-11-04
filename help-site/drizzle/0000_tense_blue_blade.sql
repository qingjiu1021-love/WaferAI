CREATE TABLE `codes` (
	`target` text NOT NULL,
	`purpose` text NOT NULL,
	`hash` text NOT NULL,
	`expires` integer NOT NULL,
	`attempts` integer DEFAULT 0 NOT NULL,
	PRIMARY KEY(`target`, `purpose`)
);
--> statement-breakpoint
CREATE TABLE `flows` (
	`state` text PRIMARY KEY NOT NULL,
	`poll_hash` text NOT NULL,
	`provider` text NOT NULL,
	`verifier` text NOT NULL,
	`nonce` text NOT NULL,
	`expires` integer NOT NULL,
	`user_id` text,
	`status` text DEFAULT 'pending' NOT NULL,
	`message` text DEFAULT '' NOT NULL,
	FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON UPDATE no action ON DELETE cascade
);
--> statement-breakpoint
CREATE UNIQUE INDEX `flows_poll_hash_unique` ON `flows` (`poll_hash`);--> statement-breakpoint
CREATE TABLE `identities` (
	`kind` text NOT NULL,
	`subject` text NOT NULL,
	`user_id` text NOT NULL,
	`password` text,
	PRIMARY KEY(`kind`, `subject`),
	FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON UPDATE no action ON DELETE cascade
);
--> statement-breakpoint
CREATE INDEX `identity_user` ON `identities` (`user_id`);--> statement-breakpoint
CREATE TABLE `limits` (
	`key` text PRIMARY KEY NOT NULL,
	`started` integer NOT NULL,
	`count` integer NOT NULL
);
--> statement-breakpoint
CREATE TABLE `sessions` (
	`hash` text PRIMARY KEY NOT NULL,
	`user_id` text NOT NULL,
	`expires` integer NOT NULL,
	FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON UPDATE no action ON DELETE cascade
);
--> statement-breakpoint
CREATE INDEX `session_expiry` ON `sessions` (`expires`);--> statement-breakpoint
CREATE TABLE `tickets` (
	`hash` text PRIMARY KEY NOT NULL,
	`user_id` text NOT NULL,
	`expires` integer NOT NULL,
	FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON UPDATE no action ON DELETE cascade
);
--> statement-breakpoint
CREATE TABLE `users` (
	`id` text PRIMARY KEY NOT NULL,
	`name` text NOT NULL,
	`created_at` integer NOT NULL,
	`policy` text NOT NULL
);
