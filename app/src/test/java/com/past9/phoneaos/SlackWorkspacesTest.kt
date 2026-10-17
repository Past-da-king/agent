package com.past9.phoneaos

import com.past9.phoneaos.tools.ComposioConnect
import com.past9.phoneaos.tools.Connection
import com.past9.phoneaos.ui.screens.duplicateGroups
import com.past9.phoneaos.ui.screens.identityOf
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Slack, Notion and friends: one person, several workspaces. A second one must be allowed, not read as a copy of the first. */
class SlackWorkspacesTest {
    @Test fun aWorkspaceNameIsFoundWhereSlackKeepsIt() {
        assertEquals("Acme HQ", ComposioConnect.identity(JSONObject("""{"data":{"team":{"name":"Acme HQ","id":"T01"}}}""")))
        assertEquals("northwind", ComposioConnect.identity(JSONObject("""{"user_info":{"team":{"domain":"northwind"}}}""")))
        assertEquals("Harbor", ComposioConnect.identity(JSONObject("""{"data":{"team_name":"Harbor"}}""")))
        assertEquals("sam@example.com", ComposioConnect.identity(JSONObject("""{"user_info":{"email":"sam@example.com"}}""")))
        assertEquals("", ComposioConnect.identity(JSONObject("""{"id":"ca_x"}""")))
    }

    @Test fun anUnnamedWorkspaceAccountGetsItsOwnLabel() {
        assertEquals("Slack workspace 1234", ComposioConnect.workspaceLabel("slack", "ca_ab1234"))
        assertEquals("Microsoft teams workspace wxyz", ComposioConnect.workspaceLabel("microsoft_teams", "ca_abwxyz"))
        assertEquals("a Gmail with no name stays unnamed: one is enough", "", ComposioConnect.workspaceLabel("gmail", "ca_ab1234"))
    }

    @Test fun twoSlackWorkspacesAreNotDuplicatesAndBothCanBeConnected() {
        val a = ComposioConnect.Account("ca_aa1111", "slack", "ACTIVE", ComposioConnect.workspaceLabel("slack", "ca_aa1111"), "", false)
        val b = ComposioConnect.Account("ca_bb2222", "slack", "ACTIVE", ComposioConnect.workspaceLabel("slack", "ca_bb2222"), "", false)
        assertFalse(ComposioConnect.sameAccount(a, b))
        val rows = listOf(a, b).map { Connection(it.id, it.slug, it.status, it.label) }
        assertTrue("both have a name, so Add shows", rows.all { identityOf(it).isNotBlank() })
        assertTrue("and neither is offered as a copy to remove", duplicateGroups(rows).isEmpty())
    }
}
