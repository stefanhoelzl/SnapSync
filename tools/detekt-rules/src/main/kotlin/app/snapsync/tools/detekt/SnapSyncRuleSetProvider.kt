package app.snapsync.tools.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/** The project's own rules, configured under `snapsync:` in `config/detekt/_base.yml`. */
class SnapSyncRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "snapsync"

    override fun instance(config: Config): RuleSet =
        RuleSet(ruleSetId, listOf(HardCodedUiText(config), HardCodedSystemText(config)))
}
