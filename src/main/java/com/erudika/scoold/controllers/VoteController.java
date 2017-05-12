package com.erudika.scoold.controllers;

/*
 * Copyright 2013-2017 Erudika. https://erudika.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For issues and patches go to: https://github.com/erudika
 */

import com.erudika.para.client.ParaClient;
import com.erudika.para.core.ParaObject;
import static com.erudika.scoold.ScooldServer.ANSWER_VOTEUP_REWARD_AUTHOR;
import static com.erudika.scoold.ScooldServer.CRITIC_IFHAS;
import static com.erudika.scoold.ScooldServer.GOODANSWER_IFHAS;
import static com.erudika.scoold.ScooldServer.GOODQUESTION_IFHAS;
import static com.erudika.scoold.ScooldServer.POST_VOTEDOWN_PENALTY_AUTHOR;
import static com.erudika.scoold.ScooldServer.POST_VOTEDOWN_PENALTY_VOTER;
import static com.erudika.scoold.ScooldServer.QUESTION_VOTEUP_REWARD_AUTHOR;
import static com.erudika.scoold.ScooldServer.SUPPORTER_IFHAS;
import static com.erudika.scoold.ScooldServer.VOTER_IFHAS;
import static com.erudika.scoold.ScooldServer.VOTEUP_REWARD_AUTHOR;
import com.erudika.scoold.core.Comment;
import com.erudika.scoold.core.Post;
import com.erudika.scoold.core.Profile;
import static com.erudika.scoold.core.Profile.Badge.CRITIC;
import static com.erudika.scoold.core.Profile.Badge.GOODANSWER;
import static com.erudika.scoold.core.Profile.Badge.GOODQUESTION;
import static com.erudika.scoold.core.Profile.Badge.SUPPORTER;
import static com.erudika.scoold.core.Profile.Badge.VOTER;
import com.erudika.scoold.core.Report;
import com.erudika.scoold.utils.ScooldUtils;
import java.util.Arrays;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 *
 * @author Alex Bogdanovski [alex@erudika.com]
 */
@Controller
public class VoteController {

	private static final Logger logger = LoggerFactory.getLogger(VoteController.class);

	private final ScooldUtils utils;
	private final ParaClient pc;

	@Inject
	public VoteController(ScooldUtils utils) {
		this.utils = utils;
		this.pc = utils.getParaClient();
	}

	@ResponseBody
	@GetMapping("/voteup/{type}/{id}")
	public Boolean voteup(@PathVariable String type, @PathVariable String id, HttpServletRequest req) {
		//addModel("voteresult", result);
		return processVoteRequest(true, type, id, req);
	}

	@ResponseBody
	@GetMapping("/votedown/{type}/{id}")
	public Boolean votedown(@PathVariable String type, @PathVariable String id, HttpServletRequest req) {
		//addModel("voteresult", result);
		return processVoteRequest(false, type, id, req);
	}

	boolean processVoteRequest(boolean isUpvote, String type, String id, HttpServletRequest req) {
		if (StringUtils.isBlank(id) || StringUtils.isBlank(type)) {
			return false;
		}
		ParaObject votable = pc.read(id);
		Profile author = null;
		Profile authUser = utils.getAuthUser(req);
		boolean result = false;
		boolean updateAuthUser = false;
		boolean updateVoter = false;
		if (votable == null || authUser == null) {
			return false;
		}

		try {
			author = pc.read(votable.getCreatorid());
			Integer votes = votable.getVotes() != null ? votable.getVotes() : 0;

			if (isUpvote && (result = pc.voteUp(votable, authUser.getId()))) {
				votes++;
				authUser.incrementUpvotes();
				updateAuthUser = true;
				int reward;

				if (votable instanceof Post) {
					Post p = (Post) votable;
					if (p.isReply()) {
						utils.addBadge(author, GOODANSWER, votes >= GOODANSWER_IFHAS, false);
						reward = ANSWER_VOTEUP_REWARD_AUTHOR;
					} else if (p.isQuestion()) {
						utils.addBadge(author, GOODQUESTION, votes >= GOODQUESTION_IFHAS, false);
						reward = QUESTION_VOTEUP_REWARD_AUTHOR;
					} else {
						reward = VOTEUP_REWARD_AUTHOR;
					}
				} else {
					reward = VOTEUP_REWARD_AUTHOR;
				}

				if (author != null && reward > 0) {
					author.addRep(reward);
					updateVoter = true;
				}
			} else if (!isUpvote && (result = pc.voteDown(votable, authUser.getId()))) {
				votes--;
				authUser.incrementDownvotes();
				updateAuthUser = true;

				if (votable instanceof Comment && votes <= -5) {
					//treat comment as offensive or spam - hide
					((Comment) votable).setHidden(true);
				} else if (votable instanceof Post && votes <= -5) {
					Post p = (Post) votable;
					//mark post for closing
					Report rep = new Report();
					rep.setParentid(id);
					rep.setLink(p.getPostLink(false, false));
					rep.setDescription(utils.getLang(req).get("posts.forclosing"));
					rep.setSubType(Report.ReportType.OTHER);
					rep.setAuthorName("System");
					rep.create();
				}
				if (author != null) {
					author.removeRep(POST_VOTEDOWN_PENALTY_AUTHOR);
					updateVoter = true;
					//small penalty to voter
					authUser.removeRep(POST_VOTEDOWN_PENALTY_VOTER);
				}
			}
		} catch (Exception ex) {
			logger.error(null, ex);
		}
		utils.addBadgeOnce(authUser, SUPPORTER, authUser.getUpvotes() >= SUPPORTER_IFHAS);
		utils.addBadgeOnce(authUser, CRITIC, authUser.getDownvotes() >= CRITIC_IFHAS);
		utils.addBadgeOnce(authUser, VOTER, authUser.getTotalVotes() >= VOTER_IFHAS);

		if (updateVoter) {
			pc.update(author);
		}
		if (updateAuthUser) {
			pc.update(authUser);
		}
		if (updateAuthUser && updateVoter) {
			pc.updateAll(Arrays.asList(author, authUser));
		}
		return result;
	}
}