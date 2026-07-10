package com.pixovid.backend.openrouter;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Port of apps/backend/src/routes/models.ts. All routes require auth (enforced by SecurityConfig). */
@RestController
@RequestMapping("/api/models")
public class ModelsController {

  private final OpenRouterClient openRouter;

  public ModelsController(OpenRouterClient openRouter) {
    this.openRouter = openRouter;
  }

  /** `/api/models` and `/api/models/video` -> video models (kept for backwards compat). */
  @GetMapping
  public List<VideoModel> models() {
    return openRouter.listVideoModels();
  }

  @GetMapping("/video")
  public List<VideoModel> videoModels() {
    return openRouter.listVideoModels();
  }

  @GetMapping("/image")
  public List<VideoModel> imageModels() {
    return openRouter.listImageModels();
  }

  /** Selectable face-swap models: local FaceFusion + reference-capable OpenRouter edit models. */
  @GetMapping("/swap")
  public List<SwapModelOption> swapModels() {
    return openRouter.listSwapModels();
  }
}
