package com.github.libretube.ui.activities

import android.os.Bundle
import androidx.core.view.isGone
import com.github.libretube.databinding.ActivityHelpBinding
import com.github.libretube.helpers.IntentHelper
import com.github.libretube.ui.base.BaseActivity
import com.google.android.material.card.MaterialCardView

class HelpActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // BetterTube's help lives on its GitHub page: the README and the issue tracker
        setupCard(binding.faq, AboutActivity.GITHUB_URL)
        setupCard(binding.matrix, AboutActivity.ISSUES_URL)
        binding.mastodon.isGone = true
        binding.lemmy.isGone = true
    }

    private fun setupCard(card: MaterialCardView, link: String) {
        card.setOnClickListener {
            IntentHelper.openLinkFromHref(this, supportFragmentManager, link)
        }
    }

}
