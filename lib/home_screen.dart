import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:my_editor/theme/app_theme.dart';
import 'package:my_editor/routing/app_router.dart';
import 'package:my_editor/widgets/glass_panel.dart';
import 'package:my_editor/editor/editor_screen.dart';
import 'package:my_editor/core/project_state.dart';
import 'package:my_editor/routing/custom_transitions.dart';
import 'package:image_picker/image_picker.dart';
import 'package:my_editor/core/project_media_manager.dart'; // Import project_media_manager
import 'package:my_editor/services/project_file_service.dart'; // Import ProjectFileService

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      body: Container(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              AppColors.gradientStart,
              AppColors.gradientEnd,
            ],
          ),
        ),
        child: CustomScrollView(
          slivers: [
            _buildAppBar(context),
            SliverPadding(
              padding: const EdgeInsets.all(16.0),
              sliver: SliverToBoxAdapter(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _buildNewProjectButton(context, ref),
                    const SizedBox(height: 16), // Spacing between buttons
                    _buildOpenProjectButton(context, ref), // Add the new button here
                    const SizedBox(height: 32),
                    _buildAISectionPlaceholder(),
                    const SizedBox(height: 32),
                    _buildSectionHeader(context, "Recent Projects"),
                  ],
                ),
              ),
            ),
            _buildRecentProjectList(context, ref),
            SliverPadding(
              padding: const EdgeInsets.all(16.0),
              sliver: SliverToBoxAdapter(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const SizedBox(height: 32),
                    _buildImportMediaButton(context, ref), // Pass ref here
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  SliverAppBar _buildAppBar(BuildContext context) {
    return SliverAppBar(
      backgroundColor: Colors.transparent, // Make app bar transparent to show gradient
      elevation: 0,
      floating: true,
      centerTitle: false,
      title: Text(
        'EDIT+',
        style: AppTextStyles.headline1.copyWith(
          color: AppColors.textColor,
        ),
      ),
      actions: [
        IconButton(
          icon: Icon(Icons.settings, color: AppColors.textColor),
          onPressed: () {
            AppRouter.navigateToSettings(context);
          },
        ),
      ],
    );
  }

  Widget _buildNewProjectButton(BuildContext context, WidgetRef ref) {
    return GlassPanel(
      borderRadius: 16,
      blur: 10,
      opacity: 0.1,
      child: InkWell(
        onTap: () {
          final newProject = ProjectModel(name: 'New Project ${DateTime.now().second}');
          ref.read(projectProvider.notifier).createProject(newProject);
          AppRouter.navigateToEditor(context, projectName: newProject.name);
        },
        borderRadius: BorderRadius.circular(16),
        child: Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(vertical: 24, horizontal: 16),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(Icons.add_circle_outline, color: AppColors.accentColor, size: 36),
              const SizedBox(width: 16),
              Text(
                'New Project',
                style: AppTextStyles.headline2.copyWith(color: AppColors.accentColor),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildAISectionPlaceholder() {
    return GlassPanel(
      borderRadius: 12,
      blur: 8,
      opacity: 0.05,
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.auto_awesome, color: AppColors.textColor, size: 28),
                const SizedBox(width: 12),
                Text(
                  'AI Powered Tools',
                  style: AppTextStyles.headline2,
                ),
              ],
            ),
            const SizedBox(height: 12),
            Text(
              'Intelligent editing assistance to speed up your workflow.',
              style: AppTextStyles.bodyText2.copyWith(color: AppColors.hintTextColor),
            ),
            const SizedBox(height: 16),
            Align(
              alignment: Alignment.centerRight,
              child: OutlinedButton(
                onPressed: () {
                  // TODO: Implement AI feature navigation
                },
                child: const Text('Explore AI'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSectionHeader(BuildContext context, String title) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 16.0),
      child: Text(
        title,
        style: AppTextStyles.headline2,
      ),
    );
  }

  Widget _buildRecentProjectList(BuildContext context, WidgetRef ref) {
    final projects = ref.watch(projectProvider.notifier).getRecentProjects();

    if (projects.isEmpty) {
      return SliverToBoxAdapter(
        child: Padding(
          padding: const EdgeInsets.all(16.0),
          child: Text(
            'No recent projects. Start a new one!',
            style: AppTextStyles.bodyText2.copyWith(color: AppColors.hintTextColor),
            textAlign: TextAlign.center,
          ),
        ),
      );
    }

    return SliverGrid(
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 2,
        crossAxisSpacing: 16.0,
        mainAxisSpacing: 16.0,
        childAspectRatio: 0.9, // Adjust as needed
      ),
      delegate: SliverChildBuilderDelegate(
        (context, index) {
          final project = projects[index];
          return _buildProjectThumbnail(context, ref, project);
        },
        childCount: projects.length,
      ),
    );
  }

  Widget _buildProjectThumbnail(BuildContext context, WidgetRef ref, ProjectModel project) {
    return GlassPanel(
      borderRadius: 12,
      blur: 8,
      opacity: 0.08,
      child: InkWell(
        onTap: () {
          ref.read(projectProvider.notifier).setCurrentProject(project); // Set as current project
          AppRouter.navigateToEditor(context, projectName: project.name);
        },
        borderRadius: BorderRadius.circular(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Container(
                color: AppColors.secondaryBackground, // Placeholder for actual thumbnail image
                child: Center(
                  child: Icon(Icons.videocam, size: 48, color: AppColors.hintTextColor),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(8.0),
              child: Text(
                project.name,
                style: AppTextStyles.bodyText1,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8.0, vertical: 4.0),
              child: Text(
                '${project.lastModified.day}/${project.lastModified.month}/${project.lastModified.year}', // Display last modified date
                style: AppTextStyles.caption,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildImportMediaButton(BuildContext context, WidgetRef ref) {
    return GlassPanel(
      borderRadius: 16,
      blur: 10,
      opacity: 0.1,
      child: InkWell(
        onTap: () async {
          final ImagePicker picker = ImagePicker();
          final XFile? video = await picker.pickVideo(source: ImageSource.gallery);

          if (video != null) {
            final newProject = ProjectModel(name: 'Imported Video Project');
            ref.read(projectProvider.notifier).createProject(newProject);
            ref.read(projectProvider.notifier).setCurrentProject(newProject);

            ref.read(projectMediaManagerProvider.notifier).addMedia(video.path, MediaType.video);

            AppRouter.navigateToEditor(context, projectName: newProject.name);
          }
        },
        borderRadius: BorderRadius.circular(16),
        child: Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(vertical: 24, horizontal: 16),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(Icons.folder_open, color: AppColors.textColor, size: 36),
              const SizedBox(width: 16),
              Text(
                'Import Media',
                style: AppTextStyles.headline2,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildOpenProjectButton(BuildContext context, WidgetRef ref) {
    return GlassPanel(
      borderRadius: 16,
      blur: 10,
      opacity: 0.1,
      child: InkWell(
        onTap: () async {
          final projectFileService = ProjectFileService();
          final availableProjects = await projectFileService.listAvailableProjects();

          if (!context.mounted) return;

          if (availableProjects.isEmpty) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('No saved projects found.')),
            );
            return;
          }

          showDialog(
            context: context,
            builder: (context) {
              return AlertDialog(
                title: const Text('Load Project'),
                content: SizedBox(
                  width: double.maxFinite,
                  child: ListView.builder(
                    shrinkWrap: true,
                    itemCount: availableProjects.length,
                    itemBuilder: (context, index) {
                      final projectId = availableProjects[index];
                      return ListTile(
                        title: Text(projectId),
                        onTap: () async {
                          Navigator.of(context).pop(); // Close dialog
                          try {
                            await ref.read(projectProvider.notifier).loadProject(projectId);
                            if (!context.mounted) return;
                            AppRouter.navigateToEditor(context, projectName: ref.read(projectProvider).name);
                          } catch (e) {
                            if (!context.mounted) return;
                            ScaffoldMessenger.of(context).showSnackBar(
                              SnackBar(content: Text('Failed to load project: $e')),
                            );
                          }
                        },
                      );
                    },
                  ),
                ),
                actions: [
                  TextButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: const Text('Cancel'),
                  ),
                ],
              );
            },
          );
        },
        borderRadius: BorderRadius.circular(16),
        child: Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(vertical: 24, horizontal: 16),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(Icons.folder_open, color: AppColors.textColor, size: 36),
              const SizedBox(width: 16),
              Text(
                'Open Project',
                style: AppTextStyles.headline2,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
