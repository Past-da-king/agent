package com.past9.phoneaos.tools

/**
 * The apps people connect most, so the Connections screen has something to browse even with a
 * Composio Connect (ck_) key, which can't list Composio's catalogue. Anything else is one search away
 * (ComposioConnect.searchApps). Slugs checked against Composio Connect on 6 Oct 2026.
 */
object AppCatalog {
    fun logo(slug: String) = "https://logos.composio.dev/api/$slug"

    private fun t(slug: String, name: String, line: String) = Toolkit(slug, name, line, logo(slug), 0, noAuth = false, managedAuth = true)

    val popular: List<Toolkit> = listOf(
        t("gmail", "Gmail", "Read, search, draft and send email"),
        t("googlecalendar", "Google Calendar", "See your day, find time, add events"),
        t("googledrive", "Google Drive", "Find, read and share your files"),
        t("googledocs", "Google Docs", "Read and write documents"),
        t("googlesheets", "Google Sheets", "Read and update spreadsheets"),
        t("googletasks", "Google Tasks", "Your to-do lists"),
        t("googlemeet", "Google Meet", "Create and find meetings"),
        t("outlook", "Outlook", "Microsoft email and calendar"),
        t("microsoft_teams", "Microsoft Teams", "Chats, channels and meetings"),
        t("one_drive", "OneDrive", "Microsoft files"),
        t("slack", "Slack", "Read channels and send messages"),
        t("whatsapp", "WhatsApp", "WhatsApp Business messages"),
        t("telegram", "Telegram", "Bots and messages"),
        t("discord", "Discord", "Servers, channels and messages"),
        t("notion", "Notion", "Notes, docs and databases"),
        t("todoist", "Todoist", "Tasks and projects"),
        t("trello", "Trello", "Boards and cards"),
        t("asana", "Asana", "Team tasks and projects"),
        t("clickup", "ClickUp", "Tasks, docs and goals"),
        t("linear", "Linear", "Issues and projects"),
        t("jira", "Jira", "Issues and sprints"),
        t("confluence", "Confluence", "Team wiki pages"),
        t("airtable", "Airtable", "Bases and records"),
        t("github", "GitHub", "Repos, issues and pull requests"),
        t("gitlab", "GitLab", "Repos, issues and merge requests"),
        t("dropbox", "Dropbox", "Files and folders"),
        t("zoom", "Zoom", "Meetings and recordings"),
        t("calendly", "Calendly", "Bookings and availability"),
        t("hubspot", "HubSpot", "Contacts, deals and CRM"),
        t("salesforce", "Salesforce", "Leads, accounts and opportunities"),
        t("mailchimp", "Mailchimp", "Audiences and campaigns"),
        t("shopify", "Shopify", "Orders, products and customers"),
        t("stripe", "Stripe", "Payments, invoices and customers"),
        t("quickbooks", "QuickBooks", "Invoices and accounting"),
        t("xero", "Xero", "Invoices and accounting"),
        t("docusign", "DocuSign", "Send and track signatures"),
        t("typeform", "Typeform", "Forms and responses"),
        t("figma", "Figma", "Design files and comments"),
        t("canva", "Canva", "Designs and brand assets"),
        t("miro", "Miro", "Whiteboards"),
        t("linkedin", "LinkedIn", "Posts and profile"),
        t("twitter", "X (Twitter)", "Posts and timeline"),
        t("instagram", "Instagram", "Posts and insights"),
        t("facebook", "Facebook", "Pages and posts"),
        t("youtube", "YouTube", "Videos, playlists and channels"),
        t("reddit", "Reddit", "Posts and comments"),
        t("spotify", "Spotify", "Playback and playlists"),
        t("wordpress_com", "WordPress.com", "Posts and pages"),
        t("webflow", "Webflow", "Sites and CMS items"),
        t("zendesk", "Zendesk", "Support tickets"),
        t("intercom", "Intercom", "Conversations and contacts"),
        t("supabase", "Supabase", "Databases and projects"),
    )

    private val names = popular.associate { it.slug to it.name } + mapOf("wordpress" to "WordPress", "metaads" to "Meta Ads")

    fun name(slug: String): String = names[slug.lowercase()] ?: slug.split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}

/**
 * The agent asks to connect an app: a chat card with the app's logo, name, why, and Connect / Decline.
 * Wire format of the question text: CONNECT|slug|App name|reason
 */
data class ConnectRequest(val slug: String, val name: String, val reason: String) {
    val text get() = "CONNECT|$slug|${name.replace("|", " ")}|${reason.replace("|", " ")}"
    val logo get() = AppCatalog.logo(slug)
    /** One line for notifications and voice. */
    val summary get() = "Connect $name" + if (reason.isNotBlank()) " so I can ${reason.removePrefix("so I can ").trimEnd('.', '?')}?" else "?"

    companion object {
        const val PREFIX = "CONNECT|"
        val OPTIONS = listOf("Connect", "Decline")
        fun of(slug: String, reason: String) = ConnectRequest(slug.lowercase().trim(), AppCatalog.name(slug), reason.trim())
        fun parse(text: String): ConnectRequest? {
            if (!text.startsWith(PREFIX)) return null
            val p = text.split("|", limit = 4)
            return ConnectRequest(p.getOrElse(1) { "" }, p.getOrElse(2) { "" }.ifBlank { AppCatalog.name(p.getOrElse(1) { "" }) }, p.getOrElse(3) { "" })
        }
    }
}
