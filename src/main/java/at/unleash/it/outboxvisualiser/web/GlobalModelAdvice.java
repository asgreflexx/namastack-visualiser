package at.unleash.it.outboxvisualiser.web;

import at.unleash.it.outboxvisualiser.action.ReadOnlySourceException;
import at.unleash.it.outboxvisualiser.source.OutboxSource;
import at.unleash.it.outboxvisualiser.source.OutboxSourceRegistry;
import at.unleash.it.outboxvisualiser.source.VisualiserProperties.Role;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Model attributes every page needs, and error pages. */
@ControllerAdvice
public class GlobalModelAdvice {

    private final OutboxSourceRegistry registry;

    public GlobalModelAdvice(OutboxSourceRegistry registry) {
        this.registry = registry;
    }

    @ModelAttribute("sources")
    public List<OutboxSource> sources() {
        return registry.all();
    }

    @ModelAttribute("username")
    public String username() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    @ModelAttribute("canOperate")
    public boolean canOperate() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + Role.OPERATOR.name()).equals(a.getAuthority()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(NoSuchElementException e, Model model) {
        model.addAttribute("message", e.getMessage());
        return "error-page";
    }

    @ExceptionHandler(ReadOnlySourceException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String readOnly(ReadOnlySourceException e, Model model) {
        model.addAttribute("message", e.getMessage());
        return "error-page";
    }
}
