/*
  This file is part of Subsonic.
	Subsonic is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
	Subsonic is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
	GNU General Public License for more details.
	You should have received a copy of the GNU General Public License
	along with Subsonic. If not, see <http://www.gnu.org/licenses/>.
	Copyright 2015 (C) Scott Jackson
*/
package github.popeen.dsub.fragments;

import android.content.res.Resources;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v7.widget.GridLayoutManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import github.popeen.dsub.R;
import github.popeen.dsub.adapter.SectionAdapter;
import github.popeen.dsub.domain.MusicDirectory;
import github.popeen.dsub.domain.PodcastChannel;
import github.popeen.dsub.domain.PodcastEpisode;
import github.popeen.dsub.domain.ServerInfo;
import github.popeen.dsub.service.MusicService;
import github.popeen.dsub.service.MusicServiceFactory;
import github.popeen.dsub.service.OfflineException;
import github.popeen.dsub.service.ServerTooOldException;
import github.popeen.dsub.util.ProgressListener;
import github.popeen.dsub.util.SyncUtil;
import github.popeen.dsub.util.Constants;
import github.popeen.dsub.util.LoadingTask;
import github.popeen.dsub.util.SilentBackgroundTask;
import github.popeen.dsub.util.UserUtil;
import github.popeen.dsub.util.Util;
import github.popeen.dsub.adapter.PodcastChannelAdapter;
import github.popeen.dsub.view.UpdateView;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;

public class SelectPodcastsFragment extends SelectRecyclerFragment<Serializable> {
	private static final String TAG = SelectPodcastsFragment.class.getSimpleName();

	private boolean hasCoverArt;
	private MusicDirectory newestEpisodes;

	@Override
	public void onCreate(Bundle bundle) {
		super.onCreate(bundle);
		hasCoverArt = ServerInfo.checkServerVersion(context, "1.13") || Util.isOffline(context);
		if (Util.getPreferences(context).getBoolean(Constants.PREFERENCES_KEY_LARGE_ALBUM_ART, true) && hasCoverArt) {
			largeAlbums = true;
		}

		if(bundle != null && serialize) {
			newestEpisodes = (MusicDirectory) bundle.getSerializable(Constants.FRAGMENT_LIST2);
		}
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		if(serialize) {
			outState.putSerializable(Constants.FRAGMENT_LIST2, newestEpisodes);
		}
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if(super.onOptionsItemSelected(item)) {
			return true;
		}
		SubsonicFragment fragment;
		Bundle args;
		FragmentTransaction trans;
		switch (item.getItemId()) {
			case R.id.menu_check:
				refreshPodcasts();
				break;
			case R.id.menu_add_podcast:
				addNewPodcast();
				break;
		}

		return false;
	}

	@Override
	public void onCreateContextMenu(Menu menu, MenuInflater menuInflater, UpdateView<Serializable> updateView, Serializable item) {
		if(item instanceof PodcastChannel) {
			PodcastChannel channel = (PodcastChannel) item;
			if (!Util.isOffline(context) && UserUtil.canPodcast()) {
				menuInflater.inflate(R.menu.select_podcasts_context, menu);


				if (SyncUtil.isSyncedPodcast(context, channel.getId())) {
					menu.removeItem(R.id.podcast_menu_sync);
				} else {
					menu.removeItem(R.id.podcast_menu_stop_sync);
				}
			} else {
				menuInflater.inflate(R.menu.select_podcasts_context_offline, menu);
			}
		}else{
			onCreateContextMenuSupport(menu, menuInflater, updateView, item);
		}
		recreateContextMenu(menu);
	}

	@Override
	public boolean onContextItemSelected(MenuItem menuItem, UpdateView<Serializable> updateView, Serializable item) {
		if(item instanceof PodcastChannel) {
			PodcastChannel channel = (PodcastChannel) item;

			switch (menuItem.getItemId()) {
				case R.id.podcast_menu_sync:
					syncPodcast(channel);
					break;
				case R.id.podcast_menu_stop_sync:
					stopSyncPodcast(channel);
					break;
				case R.id.podcast_channel_info:
					displayPodcastInfo(channel);
					break;
				case R.id.podcast_channel_delete:
					deletePodcast(channel);
					break;
			}
		} else {
			return onContextItemSelected(menuItem, item);
		}

		return true;
	}

	@Override
	public int getOptionsMenu() {
		return (UserUtil.canPodcast() && !Util.isOffline(context)) ? R.menu.select_podcasts : R.menu.abstract_top_menu;
	}

	@Override
	public SectionAdapter getAdapter(List<Serializable> channels) {
		if(newestEpisodes == null || newestEpisodes.getChildrenSize() == 0) {
			return new PodcastChannelAdapter(context, channels, hasCoverArt ? getImageLoader() : null, this, largeAlbums);
		} else {
			List<String> headers = Arrays.asList(PodcastChannelAdapter.EPISODE_HEADER, PodcastChannelAdapter.CHANNEL_HEADER);

			List<MusicDirectory.Entry> episodes = newestEpisodes.getChildren(false, true);
			List<Serializable> serializableEpisodes = new ArrayList<>();

			// Put 3 in current list
			while(serializableEpisodes.size() < 3 && !episodes.isEmpty()) {
				serializableEpisodes.add(episodes.remove(0));
			}

			// Put rest in extra set
			List<Serializable> extraEpisodes = new ArrayList<>();
			extraEpisodes.addAll(episodes);

			List<List<Serializable>> sections = new ArrayList<>();
			sections.add(serializableEpisodes);
			sections.add(channels);

			return new PodcastChannelAdapter(context, headers, sections, extraEpisodes, ServerInfo.checkServerVersion(context, "1.13") ? getImageLoader() : null, this, largeAlbums);
		}
	}

	@Override
	public List<Serializable> getObjects(MusicService musicService, boolean refresh, ProgressListener listener) throws Exception {
		List<PodcastChannel> channels = musicService.getPodcastChannels(refresh, context, listener);

		if(!Util.isOffline(context) && ServerInfo.hasNewestPodcastEpisodes(context)) {
			try {
				newestEpisodes = musicService.getNewestPodcastEpisodes(10, context, listener);
			} catch (Exception e) {
				Log.e(TAG, "Failed to download newest episodes", e);
				newestEpisodes = null;
			}
		} else {
			newestEpisodes = null;
		}

		List<Serializable> serializableList = new ArrayList<>();
		serializableList.addAll(channels);

		return serializableList;
	}

	@Override
	public int getTitleResource() {
		return R.string.button_bar_podcasts;
	}

	@Override
	public void onItemClicked(UpdateView<Serializable> updateView, Serializable item) {
		if(item instanceof PodcastChannel) {
			PodcastChannel channel = (PodcastChannel) item;
			if ("error".equals(channel.getStatus())) {
				Util.toast(context, context.getResources().getString(R.string.select_podcasts_invalid_podcast_channel, channel.getErrorMessage() == null ? "error" : channel.getErrorMessage()));
			} else if ("downloading".equals(channel.getStatus())) {
				Util.toast(context, R.string.select_podcasts_initializing);
			} else {
				SubsonicFragment fragment = new SelectDirectoryFragment();
				Bundle args = new Bundle();
				args.putString(Constants.INTENT_EXTRA_NAME_PODCAST_ID, channel.getId());
				args.putString(Constants.INTENT_EXTRA_NAME_PODCAST_NAME, channel.getName());
				args.putString(Constants.INTENT_EXTRA_NAME_PODCAST_DESCRIPTION, channel.getDescription());
				fragment.setArguments(args);

				replaceFragment(fragment);
			}
		} else {
			PodcastEpisode episode = (PodcastEpisode) item;

			String status = episode.getStatus();
			if("error".equals(status)) {
				Util.toast(context, R.string.select_podcasts_error);
				return;
			} else if(!"completed".equals(status)) {
				Util.toast(context, R.string.select_podcasts_skipped);
				return;
			}

			playNow(Arrays.asList((MusicDirectory.Entry) episode));
		}
	}

	@Override
	public GridLayoutManager.SpanSizeLookup getSpanSizeLookup(final int columns) {
		return new GridLayoutManager.SpanSizeLookup() {
			@Override
			public int getSpanSize(int position) {
				SectionAdapter adapter = getCurrentAdapter();
				if(adapter != null) {
					int viewType = getCurrentAdapter().getItemViewType(position);
					if (viewType == SectionAdapter.VIEW_TYPE_HEADER || viewType == PodcastChannelAdapter.VIEW_TYPE_PODCAST_EPISODE || viewType == PodcastChannelAdapter.VIEW_TYPE_PODCAST_LEGACY) {
						return columns;
					} else {
						return 1;
					}
				} else {
					return 1;
				}
			}
		};
	}

	public void refreshPodcasts() {
		new SilentBackgroundTask<Void>(context) {
			@Override
			protected Void doInBackground() throws Throwable {				
				MusicService musicService = MusicServiceFactory.getMusicService(context);
				musicService.refreshPodcasts(context, null);
				return null;
			}

			@Override
			protected void done(Void result) {
				Util.toast(context, R.string.select_podcasts_refreshing);
			}

			@Override
			protected void error(Throwable error) {
				Util.toast(context, getErrorMessage(error), false);
			}
		}.execute();
	}
	
	private void addNewPodcast() {
		View dialogView = context.getLayoutInflater().inflate(R.layout.create_podcast, null);
		final TextView urlBox = (TextView) dialogView.findViewById(R.id.create_podcast_url);
		
		AlertDialog.Builder builder = new AlertDialog.Builder(context);
		builder.setTitle(R.string.menu_add_podcast)
			.setView(dialogView)
			.setPositiveButton(R.string.common_ok, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int id) {
					addNewPodcast(urlBox.getText().toString());
				}
			})
			.setNegativeButton(R.string.common_cancel, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int id) {
					dialog.cancel();
				}
			})
			.setCancelable(true);
		
		AlertDialog dialog = builder.create();
		dialog.show();
	}
	private void addNewPodcast(final String url) {
		new LoadingTask<Void>(context, false) {
			@Override
			protected Void doInBackground() throws Throwable {
				MusicService musicService = MusicServiceFactory.getMusicService(context);
				musicService.createPodcastChannel(url, context, null);
				return null;
			}

			@Override
			protected void done(Void result) {
				refresh();
			}

			@Override
			protected void error(Throwable error) {
				String msg;
				if (error instanceof OfflineException || error instanceof ServerTooOldException) {
					msg = getErrorMessage(error);
				} else {
					msg = context.getResources().getString(R.string.select_podcasts_created_error) + " " + getErrorMessage(error);
				}

				Util.toast(context, msg, false);
			}
		}.execute();
	}
	
	private void displayPodcastInfo(final PodcastChannel channel) {
		List<Integer> headers = new ArrayList<>();
		List<String> details = new ArrayList<>();

		if(channel.getName() != null) {
			headers.add(R.string.details_title);
			details.add(channel.getName());
		}

		headers.add(R.string.details_url);
		details.add(channel.getUrl());
		headers.add(R.string.details_status);
		details.add(channel.getStatus());

		if(channel.getErrorMessage() != null) {
			headers.add(R.string.details_error);
			details.add(channel.getErrorMessage());
		}
		if(channel.getDescription() != null) {
			headers.add(R.string.details_description);
			details.add(channel.getDescription());
		}

		Util.showDetailsDialog(context, R.string.details_title_podcast, headers, details);
	}
	
	private void deletePodcast(final PodcastChannel channel) {
		Util.confirmDialog(context, R.string.common_delete, channel.getName(), new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				new LoadingTask<Void>(context, false) {
					@Override
					protected Void doInBackground() throws Throwable {
						MusicService musicService = MusicServiceFactory.getMusicService(context);
						musicService.deletePodcastChannel(channel.getId(), context, null);
						stopSyncPodcast(channel);
						return null;
					}

					@Override
					protected void done(Void result) {
						adapter.removeItem(channel);
						Util.toast(context, context.getResources().getString(R.string.select_podcasts_deleted, channel.getName()));
					}

					@Override
					protected void error(Throwable error) {
						String msg;
						if (error instanceof OfflineException || error instanceof ServerTooOldException) {
							msg = getErrorMessage(error);
						} else {
							msg = context.getResources().getString(R.string.select_podcasts_deleted_error, channel.getName()) + " " + getErrorMessage(error);
						}

						Util.toast(context, msg, false);
					}
				}.execute();
			}
		});
	}

	private void syncPodcast(final PodcastChannel podcast) {
		new LoadingTask<MusicDirectory>(context, false) {
			@Override
			protected MusicDirectory doInBackground() throws Throwable {
				MusicService musicService = MusicServiceFactory.getMusicService(context);
				return musicService.getPodcastEpisodes(true, podcast.getId(), context, this);
			}

			@Override
			protected void done(MusicDirectory result) {
				List<String> existingEpisodes = new ArrayList<String>();
				for(MusicDirectory.Entry entry: result.getChildren()) {
					String id = entry.getId();
					if(id != null) {
						existingEpisodes.add(entry.getId());
					}
				}

				SyncUtil.addSyncedPodcast(context, podcast.getId(), existingEpisodes);
			}
		}.execute();
	}

	private void stopSyncPodcast(PodcastChannel podcast) {
		SyncUtil.removeSyncedPodcast(context, podcast.getId());
	}
}